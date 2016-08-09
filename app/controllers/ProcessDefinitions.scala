package controllers

import java.util.{UUID, Date}

import com.gilt.svc.sundial.v0
import com.gilt.svc.sundial.v0.models.json._
import model._
import play.api.libs.json.Json
import play.api.mvc.Action
import util.CycleDetector

object ProcessDefinitions extends SundialController {

  def get() = Action {
    val result: Seq[v0.models.ProcessDefinition] = withSundialDao { implicit dao =>
      val definitions = dao.processDefinitionDao.loadProcessDefinitions()
      definitions.map(ModelConverter.toExternalProcessDefinition)
    }

    Ok(Json.toJson(result))
  }

  def getByProcessDefinitionName(processDefinitionName: String) = Action {
    val resultOpt: Option[v0.models.ProcessDefinition] = withSundialDao { implicit dao =>
      val definition = dao.processDefinitionDao.loadProcessDefinition(processDefinitionName)
      definition.map(ModelConverter.toExternalProcessDefinition)
    }

    resultOpt match {
      case Some(result) => Ok(Json.toJson(result))
      case _ => NotFound
    }
  }

  def putByProcessDefinitionName(processDefinitionName: String) = Action(parse.json[v0.models.ProcessDefinition]) { request =>
    if(processDefinitionName != request.body.processDefinitionName) {
      BadRequest(s"URL process definition name ($processDefinitionName) does not match body process definitiion name (${request.body.processDefinitionName})")
    } else {
      withSundialDao { implicit dao =>
        val taskDefinitionsByName = request.body.taskDefinitions.map(taskDef => taskDef.taskDefinitionName -> taskDef).toMap
        val hasCycle = request.body.taskDefinitions.exists { taskDef =>
          CycleDetector.hasCycle[v0.models.TaskDefinition](taskDef, current => {
            current.dependencies.map(_.taskDefinitionName).map(taskDefinitionsByName)
          })
        }

        if(hasCycle) {
          BadRequest("Process definition contains a cycle")
        } else {
          val existing = dao.processDefinitionDao.loadProcessDefinition(processDefinitionName)
          val existingTaskDefinitions = dao.processDefinitionDao.loadTaskDefinitionTemplates(processDefinitionName)
          val teams = request.body.subscriptions.map { sub =>
            Team(sub.name, sub.email, sub.notifyWhen)
          }
          val processDefinition = model.ProcessDefinition(processDefinitionName,
                                                          request.body.processDescription,
                                                          request.body.schedule.map(ModelConverter.toInternalSchedule),
                                                          ModelConverter.toInternalOverlapAction(request.body.overlapAction),
                                                          teams,
                                                          existing.map(_.createdAt).getOrElse(new Date()),
                                                          request.body.paused.getOrElse(false))
          val taskDefinitions = request.body.taskDefinitions.map { externalTaskDefinition =>
            val (required, optional) = externalTaskDefinition.dependencies.partition(_.successRequired)
            model.TaskDefinitionTemplate(externalTaskDefinition.taskDefinitionName,
                                 processDefinitionName,
                                 ModelConverter.toInternalExecutable(externalTaskDefinition.executable),
                                 TaskLimits(externalTaskDefinition.maxAttempts, externalTaskDefinition.maxRuntimeSeconds),
                                 TaskBackoff(externalTaskDefinition.backoffBaseSeconds, externalTaskDefinition.backoffExponent),
                                 TaskDependencies(required.map(_.taskDefinitionName), optional.map(_.taskDefinitionName)),
                                 externalTaskDefinition.requireExplicitSuccess)
          }

          // Save the process definition record
          dao.processDefinitionDao.saveProcessDefinition(processDefinition)

          // Delete all task definition templates that no longer exist
          existingTaskDefinitions.filterNot(taskDefinitionsByName contains _.name).foreach { taskDefinitionToRemove =>
            dao.processDefinitionDao.deleteTaskDefinitionTemplate(processDefinitionName, taskDefinitionToRemove.name)
          }

          // Save or update task definitions that are still around
          taskDefinitions.foreach { taskDefinition =>
            dao.processDefinitionDao.saveTaskDefinitionTemplate(taskDefinition)
          }

          Created
        }
      }
    }
  }

  def deleteByProcessDefinitionName(processDefinitionName: String) = Action {
    //TODO Archive rather than delete so that we don't break old processes
    withSundialDao { implicit dao =>
      dao.processDefinitionDao.deleteAllTaskDefinitionTemplates(processDefinitionName)
      dao.processDefinitionDao.deleteProcessDefinition(processDefinitionName)
    }

    NoContent
  }

  def postTriggerByProcessDefinitionName(processDefinitionName: String, taskDefinitionName: Option[String]) = Action {
    withSundialDao { implicit dao =>
      val trigger = ProcessTriggerRequest(UUID.randomUUID(),
                                          processDefinitionName,
                                          new Date(),
                                          taskDefinitionName.map(Seq(_)),
                                          None)
      dao.triggerDao.saveProcessTriggerRequest(trigger)
    }

    Created
  }

  def postPauseByProcessDefinitionName(processDefinitionName: String) = Action {
    withSundialDao { implicit dao =>
      val definition = dao.processDefinitionDao.loadProcessDefinition(processDefinitionName).get
      val newDefinition = definition.copy(isPaused = true)
      dao.processDefinitionDao.saveProcessDefinition(newDefinition)
    }
    Ok
  }

  def postResumeByProcessDefinitionName(processDefinitionName: String) = Action {
    withSundialDao { implicit dao =>
      val definition = dao.processDefinitionDao.loadProcessDefinition(processDefinitionName).get
      val newDefinition = definition.copy(isPaused= false)
      dao.processDefinitionDao.saveProcessDefinition(newDefinition)
    }
    Ok
  }
}
