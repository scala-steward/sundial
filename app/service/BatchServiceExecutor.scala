package service

import java.util.{Date, UUID}

import javax.inject.{Inject, Named}
import dao.SundialDao
import model._
import play.api.{Configuration, Logging}
import software.amazon.awssdk.services.batch.BatchClient
import software.amazon.awssdk.services.batch.model.{JobDetail, JobStatus}
import util._

class BatchServiceExecutor @Inject()(config: Configuration,
                                     injectedBatchClient: BatchClient,
                                     batchHelper: BatchHelper,
                                     @Named("sundialUrl") sundialUrl: String)
    extends SpecificTaskExecutor[BatchExecutable, BatchContainerState]
    with Logging {

  private val defaultJobQueue = config.get[String]("batch.job.queue")

  private implicit val batchClient = injectedBatchClient

  override protected def stateDao(implicit dao: SundialDao) =
    dao.batchContainerStateDao

  private def buildJobDefinition(jobDefinitionName: String,
                                 executable: BatchExecutable,
                                 task: Task): BatchJobDefinition = {

    val taskContainer = BatchContainerDefinition(
      image = s"${executable.image}:${executable.tag}",
      command = executable.command,
      vCpus = executable.vCpus,
      memory = executable.memory,
      environmentVariables = executable.environmentVariables ++ Map(
        "graphite.address" -> "companion",
        "graphite.port" -> "13290",
        "sundial.url" -> ("http://" + sundialUrl + "/"))
    )

    BatchJobDefinition(definitionName = jobDefinitionName,
                       container = taskContainer,
                       jobRoleArn = executable.jobRoleArn)
  }

  override protected def actuallyStartExecutable(
      executable: BatchExecutable,
      task: Task)(implicit dao: SundialDao): BatchContainerState = {
    val desiredTaskDefinition =
      buildJobDefinition(task.taskDefinitionName, executable, task)
    val jobDefNameOpt = {
      // get the latest revision and see if it matches what we want to run
      val latestOpt = batchHelper.describeJobDefinition(task.taskDefinitionName)
      logger.debug(s"Result of Batch describe task definition: $latestOpt")
      if (latestOpt.exists(batchHelper.matches(_, desiredTaskDefinition))) {
        logger.debug("Batch job definition matched desired")
        latestOpt.map(_.jobDefinitionName())
      } else {
        logger.debug("Batch job definition didn't match desired")
        None
      }

    }

    val jobDefName = jobDefNameOpt match {
      case Some(arn) => arn
      case _ =>
        logger.debug("Registering Batch job definition")
        val registerTaskResult =
          batchHelper.registerJobDefinition(desiredTaskDefinition)
        logger.debug(s"Register result: $registerTaskResult")
        registerTaskResult.jobDefinitionName()
    }

    val jobQueue = executable.jobQueue.getOrElse(defaultJobQueue)

    logger.debug("Starting task")
    val runJobResult = batchHelper.runJob(task.taskDefinitionName,
                                          jobQueue,
                                          jobDefName,
                                          "sundial")
    logger.debug(s"Run task result: $runJobResult")

    val jobName = runJobResult.jobName()
    val jobId = UUID.fromString(runJobResult.jobId())

    BatchContainerState(task.id,
                        new Date(),
                        jobName,
                        jobId,
                        None,
                        ExecutorStatus.Initializing)
  }

  override protected def actuallyRefreshState(state: BatchContainerState)(
      implicit dao: SundialDao): BatchContainerState = {
    logger.debug(s"Refresh state for $state")
    // If the job name is null, this never started so we can't update the state
    if (state.jobName == null) {
      logger.debug(s"State: $state")
      state
    } else {
      // See what's the latest from the ECS task
      val batchJobOpt = batchHelper.describeJob(state.jobId)
      logger.debug(s"Describe task result from Batch: $batchJobOpt")
      batchJobOpt match {
        case Some(batchJob) =>
          val batchStatus = batchJob.status()
          logger.debug(s"Task status: $batchStatus")
          require(batchJob.jobId() == state.jobId.toString) // and the same arn
          val (exitCode, exitReason) = getTaskExitCodeAndReason(batchJob)
          val logStreamName = batchJob.container().logStreamName()
          state.copy(
            status =
              batchStatusToSundialStatus(batchStatus, exitCode, exitReason),
            logStreamName = Option(logStreamName))
        case _ =>
          if (!state.status.isDone && !state.status
                .isInstanceOf[ExecutorStatus.Failed]) {
            state.copy(
              status = ExecutorStatus.Failed(
                Some("Couldn't find running job in Batch")))
          } else {
            state
          }
      }
    }
  }

  override protected def actuallyKillExecutable(
      state: BatchContainerState,
      task: Task,
      reason: String)(implicit dao: SundialDao): Unit = {
    logger.info(
      s"Sundial requesting Batch to kill task ${task.taskDefinitionName} with Sundial ID ${task.id.toString} and job name ${state.jobName}")
    batchHelper.stopTask(state.jobName, reason)
  }

  private def batchStatusToSundialStatus(
      batchStatus: JobStatus,
      exitCode: Option[Int],
      exitReason: Option[String]): ExecutorStatus = {
    (batchStatus, exitCode, exitReason) match {
      case (JobStatus.RUNNING, _, _)                            => ExecutorStatus.Running
      case (JobStatus.SUBMITTED, _, _)                          => BatchExecutorStatus.Submitted
      case (JobStatus.PENDING, _, _)                            => BatchExecutorStatus.Pending
      case (JobStatus.RUNNABLE, _, _)                           => BatchExecutorStatus.Runnable
      case (JobStatus.STARTING, _, _)                           => BatchExecutorStatus.Starting
      case (JobStatus.SUCCEEDED, _, _)                          => ExecutorStatus.Succeeded
      case (JobStatus.FAILED, Some(exitCode), Some(exitReason)) =>
        // The task has stopped running and the application threw an exception
        ExecutorStatus.Failed(
          Some(s"Exit code $exitCode, Exit reason $exitReason"))

      case (JobStatus.FAILED, Some(exitCode), None) =>
        // The task has stopped running and the application threw an exception
        ExecutorStatus.Failed(Some(s"Exit code $exitCode"))

      case (JobStatus.FAILED, None, Some(exitReason)) =>
        // The task has stopped running and the container failed for some unknown ECS related issue
        ExecutorStatus.Failed(
          Some(s"Container stopped, no exit code, exit reason: $exitReason"))

      case (JobStatus.FAILED, None, None) =>
        ExecutorStatus.Failed(Some("Container stopped, no exit code"))

      case _ =>
        throw new RuntimeException(
          "Not sure how to parse Batch Status (" + batchStatus + ") exitCode (" + exitCode + ")")
    }
  }

  private def getTaskExitCodeAndReason(
      batchJob: JobDetail): (Option[Int], Option[String]) = {
    val container = batchJob.container()
    val exitCode = Option(container.exitCode())
    val exitReason = Option(container.reason())
    (exitCode.map(_.toInt), exitReason)
  }
}
