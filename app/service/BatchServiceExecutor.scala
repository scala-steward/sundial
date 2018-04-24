package service

import java.util.{Date, UUID}
import javax.inject.{Inject, Named}

import com.amazonaws.services.batch.AWSBatch
import com.amazonaws.services.batch.model.JobDetail
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.simpledb.AmazonSimpleDB
import dao.SundialDao
import model._
import play.api.{Configuration, Logger}
import util._

class BatchServiceExecutor @Inject() (config: Configuration,
                                          injectedBatchClient: AWSBatch,
                                          batchHelper: BatchHelper,
                                          s3Client: AmazonS3,
                                          sdbClient: AmazonSimpleDB,
                                          @Named("sundialUrl") sundialUrl :String) extends SpecificTaskExecutor[BatchExecutable, BatchContainerState] {

  private val defaultJobQueue = config.get[String]("batch.job.queue")

  private implicit val batchClient = injectedBatchClient

  override protected def stateDao(implicit dao: SundialDao) = dao.batchContainerStateDao

  private def buildJobDefinition(jobDefinitionName: String, executable: BatchExecutable, task: Task): BatchJobDefinition = {

    val taskContainer = BatchContainerDefinition(
      image = s"${executable.image}:${executable.tag}",
      command = executable.command,
      vCpus = executable.vCpus,
      memory = executable.memory,
      environmentVariables = executable.environmentVariables ++ Map("graphite.address" -> "companion",
        "graphite.port" -> "13290",
        "sundial.url" -> ("http://" + sundialUrl +"/")
      ))

    BatchJobDefinition(definitionName = jobDefinitionName,
      container = taskContainer,
      jobRoleArn = executable.jobRoleArn)
  }


  override protected def actuallyStartExecutable(executable: BatchExecutable, task: Task)
                                                (implicit dao: SundialDao): BatchContainerState = {
    val desiredTaskDefinition = buildJobDefinition(task.taskDefinitionName, executable, task)
    val jobDefNameOpt = {
      // get the latest revision and see if it matches what we want to run
      val latestOpt = batchHelper.describeJobDefinition(task.taskDefinitionName)
      Logger.debug(s"Result of Batch describe task definition: $latestOpt")
      if (latestOpt.exists(batchHelper.matches(_, desiredTaskDefinition))) {
        Logger.debug("Batch job definition matched desired")
        latestOpt.map(_.getJobDefinitionName)
      } else {
        Logger.debug("Batch job definition didn't match desired")
        None
      }

    }

    val jobDefName = jobDefNameOpt match {
      case Some(arn) => arn
      case _ =>
        Logger.debug("Registering Batch job definition")
        val registerTaskResult = batchHelper.registerJobDefinition(desiredTaskDefinition)
        Logger.debug(s"Register result: $registerTaskResult")
        registerTaskResult.getJobDefinitionName
    }

    val jobQueue = executable.jobQueue.getOrElse(defaultJobQueue)

    Logger.debug("Starting task")
    val runJobResult = batchHelper.runJob(task.taskDefinitionName, jobQueue, jobDefName, "sundial")
    Logger.debug(s"Run task result: $runJobResult")

    val jobName = runJobResult.getJobName
    val jobId = UUID.fromString(runJobResult.getJobId)

    BatchContainerState(task.id, new Date(), jobName, jobId, None, ExecutorStatus.Initializing)
  }

  override protected def actuallyRefreshState(state: BatchContainerState)
                                             (implicit dao: SundialDao): BatchContainerState = {
    Logger.debug(s"Refresh state for $state")
    // If the job name is null, this never started so we can't update the state
    if(state.jobName == null) {
      Logger.debug(s"State: $state")
      state
    } else {
      // See what's the latest from the ECS task
      val batchJobOpt = batchHelper.describeJob(state.jobId)
      Logger.debug(s"Describe task result from Batch: $batchJobOpt")
      batchJobOpt match {
        case Some(batchJob) =>
          val batchStatus = batchJob.getStatus
          Logger.debug(s"Task status: $batchStatus")
          require(batchJob.getJobId == state.jobId.toString) // and the same arn
          val (exitCode, exitReason) = getTaskExitCodeAndReason(batchJob)
          val logStreamName = batchJob.getContainer.getLogStreamName()
          state.copy(status = batchStatusToSundialStatus(batchStatus, exitCode, exitReason), logStreamName = Option(logStreamName))
        case _ =>
          if(!state.status.isDone && !state.status.isInstanceOf[ExecutorStatus.Failed]) {
            state.copy(status = ExecutorStatus.Failed(Some("Couldn't find running job in Batch")))
          } else {
            state
          }
      }
    }
  }

  override protected def actuallyKillExecutable(state: BatchContainerState, task: Task, reason: String)
                                               (implicit dao: SundialDao): Unit = {
    Logger.info(s"Sundial requesting Batch to kill task ${task.taskDefinitionName} with Sundial ID ${task.id.toString} and job name ${state.jobName}")
    batchHelper.stopTask(state.jobName, reason)
  }

  private def batchStatusToSundialStatus(batchStatus: String, exitCode: Option[Int], exitReason: Option[String]): ExecutorStatus = {
    (batchStatus, exitCode, exitReason) match {
      case ("RUNNING", _, _) => ExecutorStatus.Running
      case ("SUBMITTED", _, _) => BatchExecutorStatus.Submitted
      case ("PENDING", _, _) => BatchExecutorStatus.Pending
      case ("RUNNABLE", _, _) => BatchExecutorStatus.Runnable
      case ("STARTING", _, _) => BatchExecutorStatus.Starting
      case ("SUCCEEDED", _, _) => ExecutorStatus.Succeeded
      case ("FAILED", Some(exitCode), Some(exitReason)) =>
        // The task has stopped running and the application threw an exception
        ExecutorStatus.Failed(Some(s"Exit code $exitCode, Exit reason $exitReason"))

      case ("FAILED", Some(exitCode), None) =>
        // The task has stopped running and the application threw an exception
        ExecutorStatus.Failed(Some(s"Exit code $exitCode"))

      case ("FAILED", None, Some(exitReason)) =>
        // The task has stopped running and the container failed for some unknown ECS related issue
        ExecutorStatus.Failed(Some(s"Container stopped, no exit code, exit reason: $exitReason"))

      case ("FAILED", None, None) =>
        ExecutorStatus.Failed(Some("Container stopped, no exit code"))

      case _ => throw new RuntimeException("Not sure how to parse Batch Status (" + batchStatus + ") exitCode (" + exitCode + ")")
    }
  }

  private def getTaskExitCodeAndReason(batchJob: JobDetail): (Option[Int], Option[String]) = {
    val container = batchJob.getContainer
    val exitCode = Option(container.getExitCode())
    val exitReason = Option(container.getReason)
    (exitCode.map(_.toInt), exitReason)
  }
}