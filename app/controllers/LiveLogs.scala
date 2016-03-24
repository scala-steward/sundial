package controllers

import java.util.concurrent.TimeUnit
import java.util.{Date, UUID}

import com.amazonaws.regions.Regions
import com.amazonaws.services.logs.AWSLogsClient
import com.amazonaws.services.logs.model.{GetLogEventsRequest, OutputLogEvent}
import common.SundialGlobal
import model.ContainerServiceExecutable
import play.api.mvc.{Action, Controller}
import util.{DateUtils, Json}

import scala.collection.JavaConversions._

case class TaskLogsResponse(taskId: UUID, taskDefName: String, logPath: String, nextToken: String, events: Seq[OutputLogEvent])

object LiveLogs extends Controller {

  private def taskIdForQuerystring(key: String) = UUID.fromString(key.replace("task_", ""))

  private val TaskLogToken = "task_([^_]+)_(.*)".r

  private val logsClient: AWSLogsClient = new AWSLogsClient().withRegion(Regions.valueOf(SundialGlobal.awsRegion))

  def logs(processId: String) = Action {
    Ok(views.html.liveLogs(UUID.fromString(processId)))
  }

  def logsData(processIdStr: String) = Action { request =>
    val processId = UUID.fromString(processIdStr)
    val body = request.body.asFormUrlEncoded.getOrElse(Map.empty)
    Application.daoFactory.withSundialDao { dao =>
      // Take all of the tasks that ended on or after the live log start time (or have not ended),
      // pull all of their logs starting from the given token, or from now.
      // Then, combine the logs based on timestamp and send back the combined response.
      dao.processDao.loadProcess(processId) match {
        case Some(process) =>
          // filter for the minimum time that logs can be from
          val asOf = body.get("asof").flatMap(_.headOption).map { asOfStr =>
            new Date(asOfStr.toLong - TimeUnit.MINUTES.toMillis(10))
          }.getOrElse(new Date())
          val taskLogTokens = body.collect {
            case (TaskLogToken(taskIdStr, logName), token) =>
              (UUID.fromString(taskIdStr), logName) -> token.head
          }

          val requestedTasks = taskLogTokens.map {
            case ((taskUUID, logName), token) => taskUUID
          }

          // Filter to tasks that are present in the tokens, or hadn't ended by the asof time
          val tasks = dao.processDao.loadTasksForProcess(processId).filter { task =>
            requestedTasks.contains(task.id) || task.endedAt.map(_.before(asOf)).getOrElse(true)
          }

          val taskDefinitions = dao.processDefinitionDao.loadTaskDefinitions(process.id).map { taskDef =>
            taskDef.name -> taskDef
          }.toMap

          // For each task log, fetch logs and the new token
          val logResponses = tasks.flatMap { task =>
            taskDefinitions.get(task.taskDefinitionName).map(_.executable) match {
              case Some(e: ContainerServiceExecutable) =>
                e.logPaths.flatMap { logPath =>
                  val token = taskLogTokens.get(task.id -> logPath)
                  try {
                    val response = logsClient.getLogEvents(
                      new GetLogEventsRequest()
                        .withLogGroupName("sundial/tasks-internal")
                        .withLogStreamName(s"${task.id}_${logPath}")
                        .withNextToken(token.orNull)
                        //.withStartTime(asOf.getTime)
                    )
                    val nextToken = response.getNextForwardToken
                    val events = response.getEvents
                    Some(TaskLogsResponse(task.id, task.taskDefinitionName, logPath, nextToken, events))
                  } catch {
                    case e: com.amazonaws.services.logs.model.ResourceNotFoundException => None
                  }
                }
              case _ =>
                Seq.empty
            }
          }

          // Construct the new parameters body
          val paramsMap = Map("asof" -> asOf.getTime) ++ logResponses.map { logResponse =>
            s"task_${logResponse.taskId}_${logResponse.logPath}" -> logResponse.nextToken
          }

          // Construct the log events list and sort
          val logEvents = logResponses.toList.flatMap { logResponse =>
            logResponse.events.map { event =>
              List(event.getTimestamp, logResponse.taskId, logResponse.taskDefName, event.getMessage)
            }
          }.sortBy(_.head.asInstanceOf[Long])

          // Also include information about the process
          val processInfo = Map("name" -> process.processDefinitionName,
                                "started" -> process.startedAt.getTime,
                                "status" -> process.status.statusType.toString,
                                "duration" -> DateUtils.prettyDuration(process.startedAt, process.endedAt.getOrElse(new Date)))

          val response = Map("params" -> paramsMap, "events" -> logEvents, "process" -> processInfo)

          Ok(Json.mapper().writeValueAsString(response)).as("application/json")
        case _ =>
          NotFound
      }
    }
  }

}
