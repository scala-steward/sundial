package dto

import java.text.SimpleDateFormat
import java.util.{Date, UUID}
import javax.inject.{Inject, Named, Singleton}

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{
  CannedAccessControlList,
  PutObjectRequest
}
import dao.SundialDao
import model.TaskStatus.Failure
import model._
import util.{DateUtils, Graphify}

@Singleton
class DisplayModels @Inject()(graphify: Graphify,
                              s3Client: AmazonS3,
                              @Named("s3Bucket") s3Bucket: String) {

  def fetchProcessDto(processId: UUID, generateGraph: Boolean)(
      implicit dao: SundialDao): Option[ProcessDTO] = {
    for {
      process <- dao.processDao.loadProcess(processId)
      processDef <- dao.processDefinitionDao.loadProcessDefinition(
        process.processDefinitionName)
      tasks = dao.processDao.loadTasksForProcess(processId)
      taskDefs = dao.processDefinitionDao
        .loadTaskDefinitions(process.id)
        .sortBy(_.name)
    } yield {
      val endedAt = process.endedAt

      val imageUrlOpt = if (generateGraph) {
        val imageId = UUID.randomUUID()
        val key = s"email-images/$imageId"
        val putObjectRequest =
          new PutObjectRequest(s3Bucket, key, graphify.toGraphViz(processId))
            .withCannedAcl(CannedAccessControlList.PublicRead)
        s3Client.putObject(putObjectRequest)

        Some(s3Client.getUrl(s3Bucket, key).toString())
      } else {
        None
      }

      val status = process.status match {
        case ProcessStatus.Running()    => "Running"
        case ProcessStatus.Failed(_)    => "Failed"
        case ProcessStatus.Succeeded(_) => "Succeeded"
      }

      ProcessDTO(
        id = processId,
        name = processDef.name,
        status = status,
        success = process.status.statusType == ProcessStatusType.Succeeded,
        tasks = taskDefs.map(toTaskDto(_, tasks)),
        startedAt = process.startedAt,
        endedAt = endedAt,
        durationStr =
          DateUtils.prettyDuration(process.startedAt,
                                   process.endedAt.getOrElse(new Date())),
        imageUrlOpt
      )
    }
  }

  def toTaskDto(taskDef: TaskDefinition, allTasks: Seq[Task])(
      implicit dao: SundialDao): TaskDTO = {
    val tasks =
      allTasks.filter(_.taskDefinitionName == taskDef.name).sortBy(_.startedAt)
    val attempts = tasks.size
    val startedAtOpt = tasks.headOption.map(_.startedAt)
    val endedAtOpt = tasks.reverse.headOption.flatMap(_.endedAt)
    val durationOpt = for {
      startedAt <- startedAtOpt
      endedAt <- endedAtOpt
    } yield {
      DateUtils.prettyDuration(startedAt, endedAt)
    }
    val success = tasks.exists(_.status.isInstanceOf[TaskStatus.Success])
    val finalOpt = tasks.reverse.headOption
    val finalIdOpt = finalOpt.map(_.id)
    val logs =
      finalIdOpt.map(dao.taskLogsDao.loadEventsForTask).getOrElse(Seq.empty)
    val reason = finalOpt.flatMap { task =>
      task.status match {
        case Failure(_, reason) => reason
        case _                  => None
      }
    }

    val backend = taskDef.executable match {
      case _: BatchExecutable        => TaskBackend.Batch
      case _: ECSExecutable          => TaskBackend.Ecs
      case _: ShellCommandExecutable => TaskBackend.Shell
      case _: EmrJobExecutable       => TaskBackend.Emr
    }

    TaskDTO(
      name = taskDef.name,
      finalId = finalIdOpt,
      success = success,
      attempts = attempts,
      startedAt = startedAtOpt,
      endedAt = endedAtOpt,
      durationStr = durationOpt,
      logs = logs,
      tasks = tasks,
      reason = reason,
      backend = backend
    )
  }

  def toProcessDefinitionDTO(processDefinition: ProcessDefinition)(
      implicit dao: SundialDao): ProcessDefinitionDTO = {
    val lastProcess = dao.processDao
      .findProcesses(processDefinitionName = Some(processDefinition.name),
                     limit = Some(1))
      .headOption
    val lastRunDate = lastProcess
      .map(_.startedAt)
      .getOrElse(processDefinition.createdAt)

    val nextRun = processDefinition.schedule.map { schedule =>
      schedule.nextRunAfter(lastRunDate)
    }

    val lastCompletedProcess = dao.processDao
      .findProcesses(
        processDefinitionName = Some(processDefinition.name),
        statuses =
          Some(Seq(ProcessStatusType.Succeeded, ProcessStatusType.Failed)),
        limit = Some(1))
      .headOption
    val processStartFormat = new SimpleDateFormat("M/dd H:mm z")
    val processHistory = dao.processDao
      .findProcesses(processDefinitionName = Some(processDefinition.name),
                     limit = Some(10))
      .map { process =>
        ProcessSummaryDTO(
          process.id,
          process.status.statusType,
          processStartFormat.format(process.startedAt),
          process.endedAt
            .getOrElse(new Date)
            .getTime - process.startedAt.getTime
        )
      }
    val lastDuration = for {
      process <- lastCompletedProcess
      endedAt <- process.endedAt
    } yield {
      DateUtils.prettyDuration(process.startedAt, endedAt)
    }
    ProcessDefinitionDTO(processDefinition,
                         nextRun,
                         lastProcess,
                         lastDuration,
                         processHistory)
  }

}
