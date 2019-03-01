package controllers

import java.util.concurrent.TimeUnit
import java.util.{Date, UUID}

import javax.inject.Inject
import dao.SundialDaoFactory
import model.{BatchExecutable, EmrJobExecutable}
import org.apache.commons.text.StringEscapeUtils
import play.api.mvc.InjectedController
import software.amazon.awssdk.services.cloudwatchlogs.{CloudWatchLogsClient}
import software.amazon.awssdk.services.cloudwatchlogs.model.{
  GetLogEventsRequest,
  OutputLogEvent,
}
import util.{DateUtils, Json}

import scala.collection.JavaConverters._

case class TaskLogsResponse(taskId: UUID,
                            taskDefName: String,
                            logPath: String,
                            nextToken: String,
                            events: Seq[OutputLogEvent])

class LiveLogs @Inject()(daoFactory: SundialDaoFactory,
                         logsClient: CloudWatchLogsClient)
    extends InjectedController {

  private val TaskLogToken = "task_([^_]+)_(.*)".r

  private val BATCH_LOG_GROUP = "/aws/batch/job"

  def logs(processId: String) = Action {
    Ok(views.html.liveLogs(UUID.fromString(processId)))
  }

  def logsData(processIdStr: String) = Action { request =>
    val processId = UUID.fromString(processIdStr)
    val body = request.body.asFormUrlEncoded.getOrElse(Map.empty)
    daoFactory.withSundialDao { dao =>
      // Take all of the tasks that ended on or after the live log start time (or have not ended),
      // pull all of their logs starting from the given token, or from now.
      // Then, combine the logs based on timestamp and send back the combined response.
      dao.processDao.loadProcess(processId) match {
        case Some(process) =>
          // filter for the minimum time that logs can be from
          val asOf = body
            .get("asof")
            .flatMap(_.headOption)
            .map { asOfStr =>
              new Date(asOfStr.toLong - TimeUnit.MINUTES.toMillis(10))
            }
            .getOrElse(new Date())
          val taskLogTokens = body.collect {
            case (TaskLogToken(taskIdStr, logName), token) =>
              (UUID.fromString(taskIdStr), logName) -> token.head
          }

          val requestedTasks = taskLogTokens.map {
            case ((taskUUID, _), _) => taskUUID
          }.toSeq

          // Filter to tasks that are present in the tokens, or hadn't ended by the asof time
          val tasks = dao.processDao.loadTasksForProcess(processId).filter {
            task =>
              requestedTasks.contains(task.id) || task.endedAt
                .map(_.before(asOf))
                .getOrElse(true)
          }

          val taskDefinitions = dao.processDefinitionDao
            .loadTaskDefinitions(process.id)
            .map { taskDef =>
              taskDef.name -> taskDef
            }
            .toMap

          // For each task log, fetch logs and the new token
          val logResponses = tasks.flatMap { task =>
            taskDefinitions
              .get(task.taskDefinitionName)
              .map(_.executable) match {
              case Some(e: BatchExecutable) =>
                val containerStateOpt =
                  dao.batchContainerStateDao.loadState(task.id)
                containerStateOpt.flatMap { containerState =>
                  val jobId = containerState.jobId
                  val tokenOpt = taskLogTokens.get(task.id -> jobId.toString)
                  val logStreamOpt = containerState.logStreamName
                  logStreamOpt.map { logStream =>
                    val (nextToken, events) =
                      fetchLogEvents(BATCH_LOG_GROUP, logStream, tokenOpt)
                    TaskLogsResponse(task.id,
                                     task.taskDefinitionName,
                                     jobId.toString,
                                     nextToken,
                                     events)
                  }
                }
              case Some(e: EmrJobExecutable) =>
                for {
                  state <- dao.emrJobStateDao.loadState(task.id)
                  logDetails <- e.s3LogDetailsOpt
                } yield {
                  val tokenOpt =
                    taskLogTokens.get(task.id -> state.taskId.toString)
                  val (nextToken, events) =
                    fetchLogEvents(logDetails.logGroupName,
                                   logDetails.logStreamName,
                                   tokenOpt)
                  TaskLogsResponse(task.id,
                                   task.taskDefinitionName,
                                   state.taskId.toString,
                                   nextToken,
                                   events)
                }
              case _ =>
                Seq.empty
            }
          }

          // Construct the new parameters body
          val paramsMap = Map("asof" -> asOf.getTime) ++ logResponses.map {
            logResponse =>
              s"task_${logResponse.taskId}_${logResponse.logPath}" -> logResponse.nextToken
          }

          // Construct the log events list and sort
          val logEvents = logResponses.toList
            .flatMap { logResponse =>
              logResponse.events.map { event =>
                List(event.timestamp(),
                     logResponse.taskId,
                     logResponse.taskDefName,
                     StringEscapeUtils.escapeHtml4(event.message()))
              }
            }
            .sortBy(_.head.asInstanceOf[Long])

          // Also include information about the process
          val processInfo = Map(
            "name" -> process.processDefinitionName,
            "started" -> process.startedAt.getTime,
            "status" -> process.status.statusType.toString,
            "duration" -> DateUtils.prettyDuration(
              process.startedAt,
              process.endedAt.getOrElse(new Date))
          )

          val response = Map("params" -> paramsMap,
                             "events" -> logEvents,
                             "process" -> processInfo)

          Ok(Json.mapper().writeValueAsString(response)).as("application/json")
        case _ =>
          NotFound
      }
    }
  }

  private def fetchLogEvents(logGroupName: String,
                             logStreamName: String,
                             tokenOpt: Option[String]) = {

    val getLogRequest = GetLogEventsRequest
      .builder()
      .logGroupName(logGroupName)
      .logStreamName(logStreamName)
      .nextToken(tokenOpt.orNull)
      .build()

    val response = logsClient.getLogEvents(getLogRequest)

    val nextToken = response.nextForwardToken()
    val events = response.events()
    (nextToken, events.asScala)

  }

}
