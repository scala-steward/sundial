package controllers

import java.util.UUID

import com.hbc.svc.sundial.v1
import com.hbc.svc.sundial.v1.models._
import dao.SundialDao
import model._
import play.api.Logger
import util.Conversions._

object ModelConverter {

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////
  // PROCESSES
  ///////////////////////////////////////////////////////////////////////////////////////////////////////////

  //TODO This will be RBAR if we load a lot of processes - maybe have a loadProcessesAndTasks DAO method?
  def toExternalProcess(process: model.Process)(
      implicit dao: SundialDao): v1.models.Process = {
    val tasks = dao.processDao.loadTasksForProcess(process.id)
    v1.models.Process(process.id,
                      process.processDefinitionName,
                      process.startedAt,
                      toExternalProcessStatus(process.status),
                      tasks.map(toExternalTask))
  }

  def toExternalProcessStatus(
      status: model.ProcessStatus): v1.models.ProcessStatus = status match {
    case model.ProcessStatus.Running()    => v1.models.ProcessStatus.Running
    case model.ProcessStatus.Succeeded(_) => v1.models.ProcessStatus.Succeeded
    case model.ProcessStatus.Failed(_)    => v1.models.ProcessStatus.Failed
  }

  //TODO This will be RBAR if we load a lot of tasks – maybe load all the logs/metadata at once?
  def toExternalTask(task: model.Task)(
      implicit dao: SundialDao): v1.models.Task = {
    val logs = dao.taskLogsDao.loadEventsForTask(task.id)
    val metadata = dao.taskMetadataDao.loadMetadataForTask(task.id)
    v1.models.Task(
      task.id,
      task.processId,
      task.processDefinitionName,
      task.taskDefinitionName,
      task.startedAt,
      task.endedAt,
      task.previousAttempts,
      logs.map(toExternalLogEntry),
      metadata.map(toExternalMetadataEntry),
      loadExecutableMetadata(task),
      toExternalTaskStatus(task.status)
    )
  }

  def toExternalLogEntry(entry: model.TaskEventLog): v1.models.LogEntry = {
    v1.models.LogEntry(entry.id, entry.when, entry.source, entry.message)
  }

  def toExternalMetadataEntry(
      entry: model.TaskMetadataEntry): v1.models.MetadataEntry = {
    v1.models.MetadataEntry(entry.id, entry.when, entry.key, entry.value)
  }

  def toExternalTaskStatus(status: model.TaskStatus): v1.models.TaskStatus =
    status match {
      case model.TaskStatus.Running()     => v1.models.TaskStatus.Running
      case model.TaskStatus.Success(_)    => v1.models.TaskStatus.Succeeded
      case model.TaskStatus.Failure(_, _) => v1.models.TaskStatus.Failed
    }

  def loadExecutableMetadata(task: model.Task)(
      implicit dao: SundialDao): Option[Seq[v1.models.MetadataEntry]] =
    task.executable match {
      case _: ShellCommandExecutable =>
        val stateOpt = dao.shellCommandStateDao.loadState(task.id)
        stateOpt.map { state =>
          // Use the task ID as the UUID for the metadata entry
          Seq(
            v1.models.MetadataEntry(state.taskId,
                                    state.asOf,
                                    "status",
                                    state.status.toString))
        }
      case _: ECSExecutable =>
        val stateOpt = dao.ecsContainerStateDao.loadState(task.id)
        stateOpt.map { state =>
          Seq(
            v1.models.MetadataEntry(state.taskId,
                                    state.asOf,
                                    "status",
                                    state.status.toString),
            v1.models.MetadataEntry(state.taskId,
                                    state.asOf,
                                    "taskArn",
                                    state.ecsTaskArn)
          )
        }
      case _: BatchExecutable =>
        val stateOpt = dao.batchContainerStateDao.loadState(task.id)
        stateOpt.map { state =>
          Seq(
            v1.models.MetadataEntry(state.taskId,
                                    state.asOf,
                                    "status",
                                    state.status.toString),
            v1.models.MetadataEntry(state.taskId,
                                    state.asOf,
                                    "jobId",
                                    state.jobId.toString)
          )
        }
      case _: EmrJobExecutable =>
        val stateOpt = dao.emrJobStateDao.loadState(task.id)
        stateOpt.map { state =>
          Seq(
            v1.models.MetadataEntry(state.taskId,
                                    state.asOf,
                                    "status",
                                    state.status.toString),
            v1.models.MetadataEntry(state.taskId,
                                    state.asOf,
                                    "jobId",
                                    state.taskId.toString)
          )
        }
    }

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////
  // PROCESS DEFINITIONS
  ///////////////////////////////////////////////////////////////////////////////////////////////////////////

  def toExternalProcessDefinition(processDefinition: model.ProcessDefinition)(
      implicit dao: SundialDao): v1.models.ProcessDefinition = {
    val taskDefinitions = dao.processDefinitionDao.loadTaskDefinitionTemplates(
      processDefinition.name)
    v1.models.ProcessDefinition(
      processDefinition.name,
      Some(processDefinition.isPaused),
      processDefinition.description,
      processDefinition.schedule.map(toExternalSchedule),
      taskDefinitions.map(toExternalTaskDefinitionTemplate),
      toExternalOverlapAction(processDefinition.overlapAction),
      toExternalNotifications(processDefinition.notifications)
    )
  }

  def toExternalSchedule(
      schedule: model.ProcessSchedule): v1.models.ProcessSchedule =
    schedule match {
      case model.CronSchedule(min, hr, dom, mo, dow) =>
        v1.models.CronSchedule(dow, mo, dom, hr, min)
      case model.ContinuousSchedule(buffer) =>
        v1.models.ContinuousSchedule(Some(buffer))
    }

  def toExternalOverlapAction(overlapAction: model.ProcessOverlapAction)
    : v1.models.ProcessOverlapAction = overlapAction match {
    case model.ProcessOverlapAction.Wait => v1.models.ProcessOverlapAction.Wait
    case model.ProcessOverlapAction.Terminate =>
      v1.models.ProcessOverlapAction.Terminate
  }

  def toExternalNotifications(notifications: Seq[model.Notification])
    : Option[Seq[v1.models.Notification]] = {

    def toExternalNotification
      : PartialFunction[model.Notification, v1.models.Notification] = {
      case email: EmailNotification =>
        Email(email.name,
              email.email,
              NotificationOptions
                .fromString(email.notifyAction)
                .getOrElse(NotificationOptions.OnStateChangeAndFailures))
      case pagerduty: PagerdutyNotification =>
        Pagerduty(pagerduty.serviceKey,
                  pagerduty.numConsecutiveFailures,
                  pagerduty.apiUrl)
    }

    if (notifications.isEmpty) {
      None
    } else {
      Some(notifications.map(toExternalNotification))
    }
  }

  def toExternalTaskDefinition(
      taskDefinition: model.TaskDefinition): v1.models.TaskDefinition = {
    v1.models.TaskDefinition(
      taskDefinition.name,
      toExternalDependencies(taskDefinition.dependencies),
      toExternalExecutable(taskDefinition.executable),
      taskDefinition.limits.maxAttempts,
      taskDefinition.limits.maxExecutionTimeSeconds,
      taskDefinition.backoff.seconds,
      taskDefinition.backoff.exponent,
      taskDefinition.requireExplicitSuccess
    )
  }

  def toExternalTaskDefinitionTemplate(
      taskDefinition: model.TaskDefinitionTemplate)
    : v1.models.TaskDefinition = {
    v1.models.TaskDefinition(
      taskDefinition.name,
      toExternalDependencies(taskDefinition.dependencies),
      toExternalExecutable(taskDefinition.executable),
      taskDefinition.limits.maxAttempts,
      taskDefinition.limits.maxExecutionTimeSeconds,
      taskDefinition.backoff.seconds,
      taskDefinition.backoff.exponent,
      taskDefinition.requireExplicitSuccess
    )
  }

  def toExternalDependencies(
      dependencies: model.TaskDependencies): Seq[v1.models.TaskDependency] = {
    val required = dependencies.required.map { taskDefinitionName =>
      v1.models.TaskDependency(taskDefinitionName, true)
    }
    val optional = dependencies.required.map { taskDefinitionName =>
      v1.models.TaskDependency(taskDefinitionName, false)
    }
    required ++ optional
  }

  def toExternalExecutable(
      executable: model.Executable): v1.models.TaskExecutable =
    executable match {
      case model.ShellCommandExecutable(script, env) =>
        val envAsEntries = {
          if (env.isEmpty) {
            Option.empty
          } else {
            Some(env.map {
              case (key, value) =>
                v1.models.EnvironmentVariable(key, value)
            }.toSeq)
          }
        }
        v1.models.ShellScriptCommand(script, envAsEntries)
      case model.ECSExecutable(image,
                               tag,
                               command,
                               memory,
                               cpu,
                               taskRoleArn,
                               logPaths,
                               environmentVariables) =>
        v1.models.DockerImageCommand(
          image,
          tag,
          command,
          memory,
          cpu,
          taskRoleArn,
          logPaths,
          environmentVariables.toSeq.map(variable =>
            EnvironmentVariable(variable._1, variable._2)))
      case model.BatchExecutable(image,
                                 tag,
                                 command,
                                 memory,
                                 vCpus,
                                 jobRoleArn,
                                 environmentVariables,
                                 jobQueue) =>
        v1.models.BatchImageCommand(
          image,
          tag,
          command,
          memory,
          vCpus,
          jobRoleArn,
          environmentVariables.toSeq.map(variable =>
            EnvironmentVariable(variable._1, variable._2)),
          jobQueue)
      case model.EmrJobExecutable(emrClusterDetails,
                                  jobName,
                                  region,
                                  clazz,
                                  s3JarPath,
                                  sparkConf,
                                  args,
                                  s3LogDetailsOpt,
                                  loadData,
                                  saveResults) => {
        def toEmrInstanceGroup(instanceGroupDetails: InstanceGroupDetails) = {
          val awsMarket = (instanceGroupDetails.awsMarket,
                           instanceGroupDetails.bidPriceOpt) match {
            case ("on_demand", None)      => OnDemand.OnDemand
            case ("spot", Some(bidPrice)) => Spot(BigDecimal(bidPrice))
            case _                        => OnDemand.OnDemand
          }
          EmrInstanceGroupDetails(instanceGroupDetails.instanceType,
                                  instanceGroupDetails.instanceCount,
                                  awsMarket,
                                  instanceGroupDetails.ebsVolumeSizeOpt)
        }
        val cluster = emrClusterDetails match {
          case EmrClusterDetails(Some(clusterName),
                                 None,
                                 Some(releaseLabel),
                                 applications,
                                 Some(s3LogUri),
                                 Some(masterInstanceGroup),
                                 coreInstanceGroupOpt,
                                 taskInstanceGroupOpt,
                                 ec2SubnetOpt,
                                 Some(emrServiceRole),
                                 Some(emrJobFlowRole),
                                 Some(visibleToAllUsers),
                                 configuration,
                                 false,
                                 securityConfiguration) => {
            val serviceRole =
              if (emrServiceRole == DefaultEmrServiceRole.DefaultEmrServiceRole.toString) {
                DefaultEmrServiceRole.DefaultEmrServiceRole
              } else {
                CustomEmrServiceRole(emrServiceRole)
              }
            val jobFlowRole =
              if (emrJobFlowRole == DefaultEmrJobFlowRole.DefaultEmrJobFlowRole.toString) {
                DefaultEmrJobFlowRole.DefaultEmrJobFlowRole
              } else {
                CustomEmrJobFlowRole(emrJobFlowRole)
              }
            v1.models.NewEmrCluster(
              clusterName,
              EmrReleaseLabel
                .fromString(releaseLabel)
                .getOrElse(
                  sys.error(s"Unrecognised EMR version $releaseLabel")),
              applications.map(EmrApplication.fromString(_).get),
              s3LogUri,
              toEmrInstanceGroup(masterInstanceGroup),
              coreInstanceGroupOpt.map(toEmrInstanceGroup),
              taskInstanceGroupOpt.map(toEmrInstanceGroup),
              ec2SubnetOpt,
              serviceRole,
              jobFlowRole,
              visibleToAllUsers,
              configuration,
              securityConfiguration
            )
          }
          case EmrClusterDetails(None,
                                 Some(clusterId),
                                 None,
                                 applications,
                                 None,
                                 None,
                                 None,
                                 None,
                                 None,
                                 None,
                                 None,
                                 None,
                                 None,
                                 true,
                                 None) if applications.isEmpty =>
            v1.models.ExistingEmrCluster(clusterId)
          case _ =>
            throw new IllegalArgumentException(
              s"Unexpected Cluster details: $emrClusterDetails")
        }
        val logDetailsOpt = s3LogDetailsOpt.flatMap {
          case LogDetails(logGroupName, logStreamName) =>
            Some(S3LogDetails(logGroupName, logStreamName))
        }
        val loadDataOpt = loadData.map(_.map(copyFileJob =>
          S3Cp(copyFileJob.source, copyFileJob.destination)))
        val saveResultsOpt = saveResults.map(_.map(copyFileJob =>
          S3Cp(copyFileJob.source, copyFileJob.destination)))
        v1.models.EmrCommand(cluster,
                             jobName,
                             region,
                             clazz,
                             s3JarPath,
                             sparkConf,
                             args,
                             logDetailsOpt,
                             loadDataOpt,
                             saveResultsOpt)
      }
    }

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////
  // REVERSE MAPPING
  ///////////////////////////////////////////////////////////////////////////////////////////////////////////

  def toInternalSchedule(
      schedule: v1.models.ProcessSchedule): model.ProcessSchedule =
    schedule match {
      case v1.models.ContinuousSchedule(buffer) =>
        model.ContinuousSchedule(buffer.getOrElse(0))
      case v1.models.CronSchedule(dow, mo, dom, hr, min) =>
        model.CronSchedule(min, hr, dom, mo, dow)
      case v1.models.ProcessScheduleUndefinedType(description) =>
        throw new IllegalArgumentException(
          s"Unknown schedule type with description [$description]")
    }

  def toInternalExecutable(
      executable: v1.models.TaskExecutable): model.Executable =
    executable match {
      case v1.models.ShellScriptCommand(script, envOpt) =>
        val envAsMap = envOpt match {
          case Some(env) =>
            env.map { envVar =>
              envVar.variableName -> envVar.value
            }.toMap
          case _ => Map.empty[String, String]
        }
        model.ShellCommandExecutable(script, envAsMap)

      case v1.models.DockerImageCommand(image,
                                        tag,
                                        command,
                                        memory,
                                        cpu,
                                        taskRoleArn,
                                        logPaths,
                                        environmentVariables) =>
        model.ECSExecutable(
          image,
          tag,
          command,
          memory,
          cpu,
          taskRoleArn,
          logPaths,
          environmentVariables
            .map(envVariable => envVariable.variableName -> envVariable.value)
            .toMap)

      case v1.models.BatchImageCommand(image,
                                       tag,
                                       command,
                                       memory,
                                       vCpus,
                                       jobRoleArn,
                                       environmentVariables,
                                       jobQueue) =>
        model.BatchExecutable(
          image,
          tag,
          command,
          memory,
          vCpus,
          jobRoleArn,
          environmentVariables
            .map(envVariable => envVariable.variableName -> envVariable.value)
            .toMap,
          jobQueue
        )

      case v1.models.EmrCommand(emrCluster,
                                jobName,
                                region,
                                clazz,
                                s3JarPath,
                                sparkConf,
                                args,
                                s3LogDetailsOpt,
                                loadDataOpt,
                                saveResultsOpt) => {

        def toInstanceGroupDetails(
            emrInstanceGroupDetails: EmrInstanceGroupDetails) = {
          val (awsMarket: String, bidPriceOpt: Option[Double]) =
            emrInstanceGroupDetails.awsMarket match {
              case OnDemand.OnDemand => ("on_demand", None)
              case Spot(bidPrice)    => ("spot", Some(bidPrice.toDouble))
              case _                 => ("on_demand", None)
            }
          InstanceGroupDetails(emrInstanceGroupDetails.emrInstanceType,
                               emrInstanceGroupDetails.instanceCount,
                               awsMarket,
                               bidPriceOpt,
                               emrInstanceGroupDetails.ebsVolumeSize)
        }

        val clusterDetails = emrCluster match {
          case NewEmrCluster(clusterName,
                             releaseLabel,
                             applications,
                             s3LogUri,
                             masterInstanceGroup,
                             coreInstanceGroup,
                             taskInstanceGroup,
                             ec2SubnetOpt,
                             emrServiceRole,
                             emrJobFlowRole,
                             visibleToAllUsers,
                             configuration,
                             securityConfiguration) => {
            val serviceRoleName = emrServiceRole match {
              case DefaultEmrServiceRole.DefaultEmrServiceRole =>
                DefaultEmrServiceRole.DefaultEmrServiceRole.toString
              case CustomEmrServiceRole(roleName) => roleName
              case DefaultEmrServiceRole.UNDEFINED(undefined) =>
                throw new IllegalArgumentException(
                  s"Unknown service role type: $undefined")
              case EmrServiceRoleUndefinedType(undefined) =>
                throw new IllegalArgumentException(
                  s"Unknown service role type: $undefined")
            }
            val jobFlowRoleName = emrJobFlowRole match {
              case DefaultEmrJobFlowRole.DefaultEmrJobFlowRole =>
                DefaultEmrJobFlowRole.DefaultEmrJobFlowRole.toString
              case CustomEmrJobFlowRole(roleName) => roleName
              case DefaultEmrJobFlowRole.UNDEFINED(undefined) =>
                throw new IllegalArgumentException(
                  s"Unknown job flow role type: $undefined")
              case EmrJobFlowRoleUndefinedType(undefined) =>
                throw new IllegalArgumentException(
                  s"Unknown job flow role type: $undefined")
            }
            EmrClusterDetails(
              Some(clusterName),
              None,
              Some(releaseLabel.toString),
              applications.map(_.toString),
              Some(s3LogUri),
              Some(toInstanceGroupDetails(masterInstanceGroup)),
              coreInstanceGroup.map(toInstanceGroupDetails),
              taskInstanceGroup.map(toInstanceGroupDetails),
              ec2Subnet = ec2SubnetOpt,
              Some(serviceRoleName),
              Some(jobFlowRoleName),
              Some(visibleToAllUsers),
              configuration,
              existingCluster = false,
              securityConfiguration
            )
          }
          case ExistingEmrCluster(clusterId) =>
            EmrClusterDetails(clusterName = None,
                              clusterId = Some(clusterId),
                              existingCluster = true)
          case EmrClusterUndefinedType(undefinedType) => {
            Logger.error(s"UnsupportedClusterType($undefinedType)")
            throw new IllegalArgumentException(
              s"Cluster Type not supported: $undefinedType")
          }
        }
        val logDetailsOpt = s3LogDetailsOpt.map {
          case S3LogDetails(logGroupName, logStreamName) =>
            LogDetails(logGroupName, logStreamName)
        }
        val loadData = loadDataOpt.map(
          _.map(s3Cp => CopyFileJob(s3Cp.source, s3Cp.destination)))
        val saveResults = saveResultsOpt.map(
          _.map(s3Cp => CopyFileJob(s3Cp.source, s3Cp.destination)))
        EmrJobExecutable(clusterDetails,
                         jobName,
                         region,
                         clazz,
                         s3JarPath,
                         sparkConf,
                         args,
                         logDetailsOpt,
                         loadData,
                         saveResults)
      }
      case v1.models.TaskExecutableUndefinedType(description) =>
        throw new IllegalArgumentException(
          s"Unknown executable type with description [$description]")
    }

  def toInternalOverlapAction(
      overlap: v1.models.ProcessOverlapAction): model.ProcessOverlapAction =
    overlap match {
      case v1.models.ProcessOverlapAction.Wait =>
        model.ProcessOverlapAction.Wait
      case v1.models.ProcessOverlapAction.Terminate =>
        model.ProcessOverlapAction.Terminate
      case _: v1.models.ProcessOverlapAction.UNDEFINED =>
        throw new IllegalArgumentException("Unknown overlap action")
    }

  def toInternalTaskStatusType(
      status: v1.models.TaskStatus): model.TaskStatusType = status match {
    case v1.models.TaskStatus.Starting  => model.TaskStatusType.Running
    case v1.models.TaskStatus.Pending   => model.TaskStatusType.Running
    case v1.models.TaskStatus.Submitted => model.TaskStatusType.Running
    case v1.models.TaskStatus.Runnable  => model.TaskStatusType.Running
    case v1.models.TaskStatus.Succeeded => model.TaskStatusType.Success
    case v1.models.TaskStatus.Failed    => model.TaskStatusType.Failure
    case v1.models.TaskStatus.Running   => model.TaskStatusType.Running
    case _: v1.models.TaskStatus.UNDEFINED =>
      throw new IllegalArgumentException("Unknown task status type")
  }

  def toInternalProcessStatusType(
      status: v1.models.ProcessStatus): model.ProcessStatusType = status match {
    case v1.models.ProcessStatus.Succeeded => model.ProcessStatusType.Succeeded
    case v1.models.ProcessStatus.Failed    => model.ProcessStatusType.Failed
    case v1.models.ProcessStatus.Running   => model.ProcessStatusType.Running
    case _: v1.models.ProcessStatus.UNDEFINED =>
      throw new IllegalArgumentException("Unknown process status type")
  }

  def toInternalLogEntry(taskId: UUID,
                         entry: v1.models.LogEntry): model.TaskEventLog = {
    model.TaskEventLog(entry.logEntryId,
                       taskId,
                       entry.when,
                       entry.source,
                       entry.message)
  }

  def toInternalMetadataEntry(
      taskId: UUID,
      entry: v1.models.MetadataEntry): model.TaskMetadataEntry = {
    model.TaskMetadataEntry(entry.metadataEntryId,
                            taskId,
                            entry.when,
                            entry.key,
                            entry.value)
  }

  def toInternalNotification
    : PartialFunction[v1.models.Notification, model.Notification] = {
    case email: Email =>
      EmailNotification(email.name, email.email, email.notifyWhen.toString)
    case pagerduty: Pagerduty =>
      PagerdutyNotification(pagerduty.serviceKey,
                            pagerduty.apiUrl,
                            pagerduty.numConsecutiveFailures)
    case NotificationUndefinedType(notificationTypeName) =>
      throw new IllegalArgumentException(
        s"Unknown notification type [$notificationTypeName]")
  }

}
