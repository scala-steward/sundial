package controllers

import java.util.{Date, UUID}

import com.gilt.svc.sundial.v0
import com.gilt.svc.sundial.v0.models.json._
import model.ReportedTaskStatus
import org.joda.time.DateTime
import play.api.libs.json.Json
import play.api.mvc.Action

import util.Conversions._

object Tasks extends SundialController {

  def get(processDefinitionName: String,
          taskDefinitionName: String,
          allowedStatuses: List[v0.models.TaskStatus],
          startTime: Option[DateTime],
          endTime: Option[DateTime],
          limit: Option[Int]) = Action {

    val allowedStatusTypes = {
      if(allowedStatuses.isEmpty) {
        None
      } else {
        Some(allowedStatuses.map(ModelConverter.toInternalTaskStatusType))
      }
    }
    val result = withSundialDao { implicit dao =>
      val tasks = dao.processDao.findTasks(Some(processDefinitionName),
                                           Some(taskDefinitionName),
                                           startTime,
                                           endTime,
                                           allowedStatusTypes,
                                           limit)
      tasks.map(ModelConverter.toExternalTask)
    }

    Ok(Json.toJson(result))
  }

  def postLogEntriesByTaskId(taskId: UUID) = Action(parse.json[List[v0.models.LogEntry]]) { request =>
    withSundialDao { implicit dao =>
      val events = request.body.map(ModelConverter.toInternalLogEntry(taskId, _))
      dao.taskLogsDao.saveEvents(events)
    }

    Created
  }

  def postMetadataByTaskId(taskId: UUID) = Action(parse.json[List[v0.models.MetadataEntry]]) { request =>
    withSundialDao { implicit dao =>
      val entries = request.body.map(ModelConverter.toInternalMetadataEntry(taskId, _))
      dao.taskMetadataDao.saveMetadataEntries(entries)
    }

    Created
  }

  def postSucceedByTaskId(taskId: UUID) = Action {
    withSundialDao { implicit dao =>
      dao.processDao.saveReportedTaskStatus(ReportedTaskStatus(taskId, model.TaskStatus.Success(new Date())))
    }

    Created
  }

  def postFailByTaskId(taskId: UUID) = Action {
    withSundialDao { implicit dao =>
      dao.processDao.saveReportedTaskStatus(ReportedTaskStatus(taskId, model.TaskStatus.Failure(new Date(), Some("Marked as failed via API"))))
    }

    Created
  }

}
