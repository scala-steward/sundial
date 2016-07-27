package controllers

import java.util.UUID

import com.gilt.svc.sundial.v0
import com.gilt.svc.sundial.v0.models.EnvironmentVariable
import dao.SundialDao
import model.{ContainerServiceExecutable, ShellCommandExecutable}
import util.Conversions._

object ModelConverter {

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////
  // PROCESSES
  ///////////////////////////////////////////////////////////////////////////////////////////////////////////

  //TODO This will be RBAR if we load a lot of processes - maybe have a loadProcessesAndTasks DAO method?
  def toExternalProcess(process: model.Process)(implicit dao: SundialDao): v0.models.Process = {
    val tasks = dao.processDao.loadTasksForProcess(process.id)
    v0.models.Process(process.id,
                      process.processDefinitionName,
                      process.startedAt,
                      toExternalProcessStatus(process.status),
                      tasks.map(toExternalTask))
  }

  def toExternalProcessStatus(status: model.ProcessStatus): v0.models.ProcessStatus = status match {
    case model.ProcessStatus.Running() => v0.models.ProcessStatus.Running
    case model.ProcessStatus.Succeeded(_) => v0.models.ProcessStatus.Succeeded
    case model.ProcessStatus.Failed(_) => v0.models.ProcessStatus.Failed
  }

  //TODO This will be RBAR if we load a lot of tasks – maybe load all the logs/metadata at once?
  def toExternalTask(task: model.Task)(implicit dao: SundialDao): v0.models.Task = {
    val logs = dao.taskLogsDao.loadEventsForTask(task.id)
    val metadata = dao.taskMetadataDao.loadMetadataForTask(task.id)
    v0.models.Task(task.id,
                   task.processId,
                   task.processDefinitionName,
                   task.taskDefinitionName,
                   task.startedAt,
                   task.endedAt,
                   task.previousAttempts,
                   logs.map(toExternalLogEntry),
                   metadata.map(toExternalMetadataEntry),
                   loadExecutableMetadata(task),
                   toExternalTaskStatus(task.status))
  }

  def toExternalLogEntry(entry: model.TaskEventLog): v0.models.LogEntry = {
    v0.models.LogEntry(entry.id, entry.when, entry.source, entry.message)
  }

  def toExternalMetadataEntry(entry: model.TaskMetadataEntry): v0.models.MetadataEntry = {
    v0.models.MetadataEntry(entry.id, entry.when, entry.key, entry.value)
  }

  def toExternalTaskStatus(status: model.TaskStatus): v0.models.TaskStatus = status match {
    case model.TaskStatus.Running() => v0.models.TaskStatus.Running
    case model.TaskStatus.Success(_) => v0.models.TaskStatus.Succeeded
    case model.TaskStatus.Failure(_, _) => v0.models.TaskStatus.Failed
  }

  def loadExecutableMetadata(task: model.Task)(implicit dao: SundialDao): Option[Seq[v0.models.MetadataEntry]] = task.executable match {
    case e: ShellCommandExecutable =>
      val stateOpt = dao.shellCommandStateDao.loadState(task.id)
      stateOpt.map { state =>
        // Use the task ID as the UUID for the metadata entry
        Seq(v0.models.MetadataEntry(state.taskId, state.asOf, "status", state.status.toString))
      }
    case e: ContainerServiceExecutable =>
      val stateOpt = dao.containerServiceStateDao.loadState(task.id)
      stateOpt.map { state =>
        Seq(v0.models.MetadataEntry(state.taskId, state.asOf, "status", state.status.toString),
            v0.models.MetadataEntry(state.taskId, state.asOf, "taskArn", state.ecsTaskArn))
      }
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////
  // PROCESS DEFINITIONS
  ///////////////////////////////////////////////////////////////////////////////////////////////////////////

  def toExternalProcessDefinition(processDefinition: model.ProcessDefinition)(implicit dao: SundialDao): v0.models.ProcessDefinition = {
    val taskDefinitions = dao.processDefinitionDao.loadTaskDefinitionTemplates(processDefinition.name)
    v0.models.ProcessDefinition(processDefinition.name,
                                Some(processDefinition.isPaused),
                                processDefinition.description,
                                processDefinition.schedule.map(toExternalSchedule),
                                taskDefinitions.map(toExternalTaskDefinitionTemplate),
                                toExternalOverlapAction(processDefinition.overlapAction),
                                processDefinition.teams.map(toExternalSubscription))
  }

  def toExternalSchedule(schedule: model.ProcessSchedule): v0.models.ProcessSchedule = schedule match {
    case model.CronSchedule(min, hr, dom, mo, dow) => v0.models.CronSchedule(dow, mo, dom, hr, min)
    case model.ContinuousSchedule(buffer) => v0.models.ContinuousSchedule(Some(buffer))
  }

  def toExternalOverlapAction(overlapAction: model.ProcessOverlapAction): v0.models.ProcessOverlapAction = overlapAction match {
    case model.ProcessOverlapAction.Wait => v0.models.ProcessOverlapAction.Wait
    case model.ProcessOverlapAction.Terminate => v0.models.ProcessOverlapAction.Terminate
  }

  def toExternalSubscription(team: model.Team): v0.models.Subscription = {
    v0.models.Subscription(team.name, team.email)
  }

  def toExternalTaskDefinition(taskDefinition: model.TaskDefinition): v0.models.TaskDefinition = {
    v0.models.TaskDefinition(taskDefinition.name,
                             toExternalDependencies(taskDefinition.dependencies),
                             toExternalExecutable(taskDefinition.executable),
                             taskDefinition.limits.maxAttempts,
                             taskDefinition.limits.maxExecutionTimeSeconds,
                             taskDefinition.backoff.seconds,
                             taskDefinition.backoff.exponent,
                             taskDefinition.requireExplicitSuccess)
  }

  def toExternalTaskDefinitionTemplate(taskDefinition: model.TaskDefinitionTemplate): v0.models.TaskDefinition = {
    v0.models.TaskDefinition(taskDefinition.name,
      toExternalDependencies(taskDefinition.dependencies),
      toExternalExecutable(taskDefinition.executable),
      taskDefinition.limits.maxAttempts,
      taskDefinition.limits.maxExecutionTimeSeconds,
      taskDefinition.backoff.seconds,
      taskDefinition.backoff.exponent,
      taskDefinition.requireExplicitSuccess)
  }


  def toExternalDependencies(dependencies: model.TaskDependencies): Seq[v0.models.TaskDependency] = {
    val required = dependencies.required.map { taskDefinitionName =>
      v0.models.TaskDependency(taskDefinitionName, true)
    }
    val optional = dependencies.required.map { taskDefinitionName =>
      v0.models.TaskDependency(taskDefinitionName, false)
    }
    required ++ optional
  }

  def toExternalExecutable(executable: model.Executable): v0.models.TaskExecutable = executable match {
    case model.ShellCommandExecutable(script, env) =>
      val envAsEntries = {
        if(env.isEmpty) {
          Option.empty
        } else {
          Some(env.map { case (key, value) =>
            v0.models.EnvironmentVariable(key, value)
          }.toSeq)
        }
      }
      v0.models.ShellScriptCommand(script, envAsEntries)
    case model.ContainerServiceExecutable(image, tag, command, memory, cpu, taskRoleArn, logPaths, environmentVariables) =>
      v0.models.DockerImageCommand(image, tag, command, memory, cpu, taskRoleArn, logPaths, environmentVariables.toSeq.map(variable => EnvironmentVariable(variable._1, variable._2)))
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////
  // REVERSE MAPPING
  ///////////////////////////////////////////////////////////////////////////////////////////////////////////

  def toInternalSchedule(schedule: v0.models.ProcessSchedule): model.ProcessSchedule = schedule match {
    case v0.models.ContinuousSchedule(buffer) => model.ContinuousSchedule(buffer.getOrElse(0))
    case v0.models.CronSchedule(dow, mo, dom, hr, min) => model.CronSchedule(min, hr, dom, mo, dow)
    case v0.models.ProcessScheduleUndefinedType(description) =>
      throw new IllegalArgumentException(s"Unknown schedule type with description [$description]")
  }

  def toInternalExecutable(executable: v0.models.TaskExecutable): model.Executable = executable match {
    case v0.models.ShellScriptCommand(script, envOpt) =>
      val envAsMap = envOpt match {
        case Some(env) => env.map { envVar =>
          envVar.variableName -> envVar.value
        }.toMap
        case _ => Map.empty[String, String]
      }
      model.ShellCommandExecutable(script, envAsMap)

    case v0.models.DockerImageCommand(image, tag, command, memory, cpu, taskRoleArn, logPaths, environmentVariables) =>
      model.ContainerServiceExecutable(image,
                                       tag,
                                       command,
                                       memory,
                                       cpu,
                                       taskRoleArn,
                                       logPaths,
        environmentVariables.map(envVariable => envVariable.variableName -> envVariable.value).toMap
      )

    case v0.models.TaskExecutableUndefinedType(description) =>
      throw new IllegalArgumentException(s"Unknown executable type with description [$description]")
  }

  def toInternalOverlapAction(overlap: v0.models.ProcessOverlapAction): model.ProcessOverlapAction = overlap match {
    case v0.models.ProcessOverlapAction.Wait => model.ProcessOverlapAction.Wait
    case v0.models.ProcessOverlapAction.Terminate => model.ProcessOverlapAction.Terminate
    case _: v0.models.ProcessOverlapAction.UNDEFINED => throw new IllegalArgumentException("Unknown overlap action")
  }

  def toInternalTaskStatusType(status: v0.models.TaskStatus): model.TaskStatusType = status match {
    case v0.models.TaskStatus.Succeeded => model.TaskStatusType.Success
    case v0.models.TaskStatus.Failed => model.TaskStatusType.Failure
    case v0.models.TaskStatus.Running => model.TaskStatusType.Running
    case _: v0.models.TaskStatus.UNDEFINED => throw new IllegalArgumentException("Unknown task status type")
  }

  def toInternalProcessStatusType(status: v0.models.ProcessStatus): model.ProcessStatusType = status match {
    case v0.models.ProcessStatus.Succeeded => model.ProcessStatusType.Succeeded
    case v0.models.ProcessStatus.Failed => model.ProcessStatusType.Failed
    case v0.models.ProcessStatus.Running => model.ProcessStatusType.Running
    case _: v0.models.ProcessStatus.UNDEFINED => throw new IllegalArgumentException("Unknown process status type")
  }

  def toInternalLogEntry(taskId: UUID, entry: v0.models.LogEntry): model.TaskEventLog = {
    model.TaskEventLog(entry.logEntryId, taskId, entry.when, entry.source, entry.message)
  }

  def toInternalMetadataEntry(taskId: UUID, entry: v0.models.MetadataEntry): model.TaskMetadataEntry = {
    model.TaskMetadataEntry(entry.metadataEntryId, taskId, entry.when, entry.key, entry.value)
  }

}
