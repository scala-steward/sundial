package service

import java.util.{Date, UUID}

import com.amazonaws.regions.Regions
import com.amazonaws.services.ecs.AmazonECSClient
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.simpledb.AmazonSimpleDBClient
import com.amazonaws.services.simpledb.model.GetAttributesRequest
import com.amazonaws.util.StringInputStream
import common.SundialGlobal
import dao.SundialDao
import model._
import org.apache.commons.io.FilenameUtils
import play.api.Logger
import util._

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.util.control.NonFatal

class ContainerServiceExecutor() extends SpecificTaskExecutor[ContainerServiceExecutable, ContainerServiceState] {

  private lazy val config = play.Play.application.configuration

  val awsRegion = config.getString("aws.region")
  val companionImage = config.getString("companion.tag")
  val cluster = config.getString("ecs.cluster")
  val defaultCpu = config.getInt("ecs.defaultCpu")
  val defaultMemory = config.getInt("ecs.defaultMemory")

  implicit val ecsClient: AmazonECSClient = new AmazonECSClient().withRegion(Regions.valueOf(awsRegion))
  implicit val s3Client = new AmazonS3Client()
  implicit val sdbClient: AmazonSimpleDBClient = new AmazonSimpleDBClient().withRegion(Regions.valueOf(awsRegion))

  override def stateDao(implicit dao: SundialDao) = dao.containerServiceStateDao

  private def buildLogPaths(executable: ContainerServiceExecutable) = {
    val logDirectories = executable.logPaths.map(FilenameUtils.getFullPathNoEndSeparator).distinct.sorted
    logDirectories.zipWithIndex.map { case (path, ix) =>
      path -> s"task-logs-$ix"
    }
  }

  private def buildTaskDefinition(family: String, executable: ContainerServiceExecutable, task: Task): ECSTaskDefinition = {
    // We need to create a logging mount point for each log directory
    // We sort so that this is in the same order each time we check for the task definition
    val logPathsAndVolumes = buildLogPaths(executable)
    val hostrunVolume = ECSVolume("hostrun", Some("/var/run"))
    val hostrunMountPoint = ECSMountPoint(containerPath = "/var/hostrun",
                                          sourceVolume = hostrunVolume.name)
    val loggingVolumes = logPathsAndVolumes.map { case (path, volume) =>
        ECSVolume(volume, None)
    }
    val taskContainer = ECSContainerDefinition(name = "sundialTask",
                                               image = s"${executable.image}:${executable.tag}",
                                               command = executable.command,
                                               cpu = executable.cpu.getOrElse(defaultCpu),
                                               memory = executable.memory.getOrElse(defaultMemory),
                                               essential = false, // the companion will wait
                                               environmentVariables = executable.environmentVariables ++ Map("graphite.address" -> "companion",
                                                 "graphite.port" -> "13290",
                                                 "sundial.url" -> ("http://" + SundialGlobal.sundialUrl +"/")
                                               ),
                                               links = Seq(ECSContainerLink("sundialCompanion", "companion")),
                                               mountPoints = logPathsAndVolumes.map { case (path, volume) =>
                                                 ECSMountPoint(containerPath = path,
                                                               sourceVolume = volume)
                                               })
    val companionContainer = ECSContainerDefinition(name = "sundialCompanion",
                                                    image = companionImage,
                                                    command = Seq.empty,
                                                    cpu = 100,
                                                    memory = 150,
                                                    essential = true,
                                                    links = Seq.empty,
                                                    mountPoints = hostrunMountPoint +: logPathsAndVolumes.map { case (path, volume) =>
                                                      ECSMountPoint(containerPath = s"/var/log/sundial/$volume",
                                                                    sourceVolume = volume)
                                                    })
    ECSTaskDefinition(family = family,
                      containers = Seq(taskContainer, companionContainer),
                      volumes = hostrunVolume +: loggingVolumes,
                      taskRoleArn = executable.taskRoleArn)
  }

  private def buildCloudWatchConfig(family: String, executable: ContainerServiceExecutable, task: Task): String = {
    val head =
      """
        |[general]
        |state_file = /var/awslogs/state/agent-state
        |
      """.stripMargin
    val logPathMappings = buildLogPaths(executable).toMap
    val logParts = executable.logPaths.flatMap { logPath =>
      val volume = logPathMappings(FilenameUtils.getFullPathNoEndSeparator(logPath))
      val filename = FilenameUtils.getName(logPath)
      val humanReadableConfig =
      s"""
         |[$logPath]
         |file = /var/log/sundial/$volume/$filename
         |log_group_name = sundial/${task.processDefinitionName}/${task.taskDefinitionName}
         |log_stream_name = $logPath
         |
       """.stripMargin
      val internalConfig =
        s"""
         |[internal-$logPath]
         |file = /var/log/sundial/$volume/$filename
         |log_group_name = sundial/tasks-internal
         |log_stream_name = ${task.id}_$logPath
         |
       """.stripMargin
      Seq(humanReadableConfig, internalConfig)
    }
    (head +: logParts).mkString("\n")
  }

  override protected def actuallyStartExecutable(executable: ContainerServiceExecutable, task: Task)
                                                (implicit dao: SundialDao): ContainerServiceState = {
    val family = getFamilyName(task.processDefinitionName, task.taskDefinitionName)
    val desiredTaskDefinition = buildTaskDefinition(family, executable, task)
    val taskDefArnOpt = {
      Logger.debug("About to query ECS to list task definition families")
      val families = ECSHelper.listTaskDefinitionFamilies(family)
      if(!families.contains(family)){
        // don't need to query ECS for task definitions if we already know the family doesn't exist
        None
      } else {
        // get the latest revision and see if it matches what we want to run
        val latest = ECSHelper.describeTaskDefinition(family, revision = None)
        Logger.debug(s"Result of ECS describe task definition: $latest")
        if(ECSHelper.matches(latest, desiredTaskDefinition)) {
          Logger.debug("ECS task definition matched desired")
          Some(latest.getTaskDefinitionArn)
        } else {
          Logger.debug("ECS task definition didn't match desired")
          None
        }
      }
    }

    val taskDefArn = taskDefArnOpt match {
      case Some(arn) => arn
      case _ =>
        Logger.debug("Registering ECS task definition")
        val registerTaskResult = ECSHelper.registerTaskDefinition(desiredTaskDefinition)
        Logger.debug(s"Register result: $registerTaskResult")
        registerTaskResult.getTaskDefinition.getTaskDefinitionArn
    }

    // Put the Cloudwatch config up in S3 so that the companion container has access to it
    val cloudwatchConfig = buildCloudWatchConfig(family, executable, task)
    Logger.debug("Uploading cloudwatch config to s3")
    s3Client.putObject(SundialGlobal.s3Bucket, s"agent-config/${task.id}", new StringInputStream(cloudwatchConfig), new ObjectMetadata())

    // Start the task, sending the task ID to the companion container as an environment variable
    val companionOverride = ECSContainerOverride(name = "sundialCompanion",
                                                 command = Seq("bash", "-c", s"TASK_ID=${task.id} /opt/gilt/sundial-companion/sundial-logs.sh"))
    Logger.debug("Starting task")
    val runTaskResult = ECSHelper.runTask(taskDefArn,
                                          cluster = cluster,
                                          startedBy = task.id.toString,
                                          overrides = Seq(companionOverride))
    Logger.debug(s"Run task result: $runTaskResult")

    val failures = runTaskResult.getFailures.asScala
    if(failures.length > 0) {
      val reasons = failures.map(x => s"(${x.getArn},${x.getReason})")
      // TODO: Sometimes RunTask will fail with reason 'AGENT'
      // Im not yet sure what this means but the github issues page provides some pointers
      // https://github.com/aws/amazon-ecs-agent/issues/24#issuecomment-94081006
      //throw new RuntimeException("Failed to RunTask. Got the following failures: " + reasons.mkString(","))
      Logger.error(s"Failures initializing task ${task.id} : ${reasons.mkString(",")}")
      dao.taskLogsDao.saveEvents(Seq(TaskEventLog(UUID.randomUUID(),
                                                  task.id,
                                                  new Date(),
                                                  "executor",
                                                  "Failed to RunTask. Got the following failures: " + reasons.mkString(","))))
      ContainerServiceState(task.id, new Date(), null, TaskExecutorStatus.Fault(Some(reasons.mkString(","))))
    } else {
      Logger.debug("No failures returned from ECS run task")
      val ecsTask = runTaskResult.getTasks.asScala.head
      val arn = ecsTask.getTaskArn

      ContainerServiceState(task.id, new Date(), arn, TaskExecutorStatus.Initializing)
    }
  }

  private def refreshMetadata(state: ContainerServiceState)
                             (implicit dao: SundialDao) {
    try {
      val attrs = sdbClient.getAttributes(new GetAttributesRequest()
        .withDomainName(SundialGlobal.sdbDomain)
        .withItemName(state.taskId.toString))
      val now = new Date()
      val entries = attrs.getAttributes.map { attr =>
        TaskMetadataEntry(UUID.randomUUID(), state.taskId, now, attr.getName, attr.getValue)
      }
      dao.taskMetadataDao.saveMetadataEntries(entries)
    } catch {
      case NonFatal(e) => Logger.error("Error refreshing metadata", e)
    }
  }

  override protected def actuallyRefreshState(state: ContainerServiceState)
                                             (implicit dao: SundialDao): ContainerServiceState = {
    Logger.debug(s"Refresh state for $state")
    // If the ARN is null, this never started so we can't update the state
    if(state.ecsTaskArn == null) {
      Logger.debug(s"State: $state")
      state
    } else {
      // Look for any new metadata from SimpleDB
      Logger.debug("About to refresh metadata")
      refreshMetadata(state)
      Logger.debug("Refreshed metadata")
      // See what's the latest from the ECS task
      val ecsTaskOpt = ECSHelper.describeTask(cluster, state.ecsTaskArn)
      Logger.debug(s"Describe task result from ECS: $ecsTaskOpt")
      ecsTaskOpt match {
        case Some(ecsTask) =>
          val ecsStatus = ecsTask.getLastStatus
          Logger.debug(s"Task status: $ecsStatus")
          require(ecsTask.getStartedBy == state.taskId.toString) // lets make sure were talking about the same taskId
          require(ecsTask.getTaskArn == state.ecsTaskArn) // and the same arn
          val (exitCode, exitReason) = getTaskExitCodeAndReason(ecsTask)
          state.copy(status = ecsStatusToSundialStatus(ecsStatus, exitCode, exitReason))
        case _ =>
          if(!state.status.isDone && !state.status.isInstanceOf[TaskExecutorStatus.Fault]) {
            state.copy(status = TaskExecutorStatus.Fault(Some("Couldn't find running task in ECS")))
          } else {
            state
          }
      }
    }
  }

  override protected def actuallyKillExecutable(state: ContainerServiceState, task: Task)
                                               (implicit dao: SundialDao): Unit = {
    Logger.info(s"Sundial requesting ECS to kill task ${task.taskDefinitionName} with Sundial ID ${task.id.toString} and ECS ID ${state.ecsTaskArn}")
    ECSHelper.stopTask(cluster, state.ecsTaskArn)
  }

  private def getFamilyName(processDefinitionName: String, taskDefinitionName: String): String = {
    processDefinitionName + "_" + taskDefinitionName
  }

  private def ecsStatusToSundialStatus(ecsStatus: String, exitCode: Option[Int], exitReason: Option[String]): TaskExecutorStatus = {
    (ecsStatus, exitCode, exitReason) match {
      case ("RUNNING", None, _) => TaskExecutorStatus.Running
      case ("RUNNING", _, _) => TaskExecutorStatus.Running // The companion container may still be running
      case ("PENDING", None, _) => TaskExecutorStatus.Initializing
      case ("PENDING", _, _) => TaskExecutorStatus.Running // The companion container may still be running
      case ("STOPPED", Some(0), _) => TaskExecutorStatus.Completed
      case ("STOPPED", Some(exitCode), Some(exitReason)) => {
        // The task has stopped running and the application threw an exception
        TaskExecutorStatus.Fault(Some(s"Exit code $exitCode, Exit reason $exitReason"))
      }
      case ("STOPPED", Some(exitCode), None) => {
        // The task has stopped running and the application threw an exception
        TaskExecutorStatus.Fault(Some(s"Exit code $exitCode"))
      }
      case ("STOPPED", None, Some(exitReason)) => {
        // The task has stopped running and the container failed for some unknown ECS related issue
        TaskExecutorStatus.Fault(Some(s"Container stopped, no exit code, exit reason: $exitReason"))
      }
      case ("STOPPED", None, None) => {
        TaskExecutorStatus.Fault(Some("Container stopped, no exit code"))
      }
      case _ => throw new RuntimeException("Not sure how to parse ECS Status (" + ecsStatus + ") exitCode (" + exitCode + ")")
    }
  }

  private def getTaskExitCodeAndReason(ecsTask: com.amazonaws.services.ecs.model.Task): (Option[Int], Option[String]) = {
    val taskContainers = ecsTask.getContainers.asScala.filter(_.getName == "sundialTask")
    require(taskContainers.size == 1, "there can only be 1 sundialTask container per task definition")
    val exitCode = Option(taskContainers.head.getExitCode())
    val exitReason = Option(taskContainers.head.getReason)
    (exitCode.map(_.toInt), exitReason)
  }
}
