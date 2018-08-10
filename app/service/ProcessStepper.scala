package service

import java.util.concurrent.TimeUnit
import java.util.{Date, UUID}
import javax.inject.Inject

import dao.SundialDao
import model._
import play.api.Logger
import service.notifications.Notification

import scala.collection.immutable.SortedMap

sealed trait TaskExecutionCondition

object TaskExecutionCondition {
  case object Allowed extends TaskExecutionCondition
  case object Pending extends TaskExecutionCondition
  case object CompletedOrProhibited extends TaskExecutionCondition

  def reduce(lhs: TaskExecutionCondition, rhs: TaskExecutionCondition) = {
    val seq = Seq(lhs, rhs)
    if (seq.contains(CompletedOrProhibited))
      CompletedOrProhibited
    else if (seq.contains(Pending))
      Pending
    else
      Allowed
  }

}

class ProcessStepper @Inject()(
    taskExecutor: TaskExecutor,
    notifications: Seq[Notification]
) {

  val EVENT_SOURCE_KEY = "scheduler"

  def step(process: Process,
           dao: SundialDao,
           metrics: SundialMetrics): Process = {
    Logger.info(s"Stepping ${process}")
    metrics.steps.incrementAndGet()
    val taskDefinitions =
      dao.processDefinitionDao.loadTaskDefinitions(process.id).filter {
        taskDefinition =>
          process.taskFilter match {
            case Some(taskNames) => taskNames.contains(taskDefinition.name)
            case _               => true
          }
      }

    Logger.debug(s"Task definitions: $taskDefinitions")

    val baseTasks = dao.processDao.loadTasksForProcess(process.id).filter {
      task =>
        process.taskFilter match {
          case Some(taskNames) => taskNames.contains(task.taskDefinitionName)
          case _               => true
        }
    }

    Logger.debug(s"Base tasks $baseTasks")

    // Throughout here, we need both the task and the definition
    val tasks = baseTasks
      .map { task =>
        task -> taskDefinitions.find(_.name == task.taskDefinitionName)
      }
      .collect {
        case (task, Some(taskDef)) => task -> taskDef
      }
      .map {
        case (task, taskDef) =>
          if (task.status.isComplete)
            task -> taskDef
          else
            refreshTaskStatus(task, taskDef, dao) -> taskDef
      }
      .map {
        case (task, taskDef) =>
          killIfNecessary(task, taskDef, dao)
      }
      .toList

    Logger.debug(s"Tasks with status: $tasks")

    val hasKillRequest =
      !dao.triggerDao.loadKillProcessRequests(process.id).isEmpty
    val currentlyRunning = tasks.filterNot(_.status.isComplete)
    val taskContext =
      new TaskContext(tasks, taskDefinitions, process, hasKillRequest)
    val byStatus = taskDefinitions.groupBy { taskDefinition =>
      taskContext.taskExecutionCondition(taskDefinition)
    }
    val allowedToRun =
      byStatus.getOrElse(TaskExecutionCondition.Allowed, Seq.empty)
    val notYet = byStatus.getOrElse(TaskExecutionCondition.Pending, Seq.empty)

    allowedToRun.foreach { taskDefinition =>
      metrics.taskStarts.incrementAndGet()
      val previousAttempts =
        taskContext.tasksForTaskDefinition(taskDefinition).size
      val task = dao.processDao.saveTask(
        Task(
          id = UUID.randomUUID(),
          taskDefinitionName = taskDefinition.name,
          processId = process.id,
          processDefinitionName = process.processDefinitionName,
          executable = taskDefinition.executable,
          previousAttempts = previousAttempts,
          startedAt = new Date(),
          status = TaskStatus.Running()
        ))
      dao.ensureCommitted()
      Logger.info(s"Starting task: $task")
      taskExecutor.startExecutable(task)(dao)
    }

    // If there are no tasks running, and no tasks left to run, we are done.
    if (allowedToRun.isEmpty && notYet.isEmpty && currentlyRunning.isEmpty) {
      val anyFailed = taskDefinitions
        .map(taskContext.mostRecentStatusForTaskDefinition)
        .exists {
          case Some(TaskStatus.Failure(_, _)) => true
          case _                              => false
        }
      val processStatus =
        if (anyFailed) {
          ProcessStatus.Failed(new Date())
        } else {
          ProcessStatus.Succeeded(new Date())
        }

      if (hasKillRequest) {
        metrics.processTerminations.incrementAndGet()
      } else {
        metrics.processFinishes.incrementAndGet()
      }

      val updated =
        dao.processDao.saveProcess(process.copy(status = processStatus))
      dao.ensureCommitted()
      notifications.foreach(_.notifyProcessFinished(process.id))
      updated
    } else {
      process
    }
  }

  private def killIfNecessary(task: Task,
                              taskDefinition: TaskDefinition,
                              dao: SundialDao): Task = {
    // Kills a task if it has run over its allotted time, or if the whole process has been killed
    task.status match {
      case TaskStatus.Running() =>
        val elapsedMillis = new Date().getTime - task.startedAt.getTime
        val isOverTime = taskDefinition.limits.maxExecutionTimeSeconds match {
          case Some(maxSeconds) =>
            val maxMillis = TimeUnit.SECONDS.toMillis(maxSeconds)
            elapsedMillis > maxMillis
          case _ =>
            false
        }
        val hasKillRequest =
          !dao.triggerDao.loadKillProcessRequests(task.processId).isEmpty

        if (isOverTime || hasKillRequest) {
          val reason = {
            if (hasKillRequest) {
              s"The task's process was requested to be killed; killing the task and marking as failed."
            } else {
              s"The underlying executable been running for $elapsedMillis ms; killing the task and marking as failed."
            }
          }

          val event = TaskEventLog(UUID.randomUUID(),
                                   task.id,
                                   new Date(),
                                   EVENT_SOURCE_KEY,
                                   reason)

          dao.taskLogsDao.saveEvents(Seq(event))
          dao.ensureCommitted()

          taskExecutor.killExecutable(task, reason)(dao)

          val updated = dao.processDao.saveTask(
            task.copy(status = TaskStatus.Failure(new Date(), Some(reason))))
          dao.ensureCommitted()
          updated
        } else {
          task
        }
      case _ =>
        task
    }
  }

  private def refreshTaskStatus(task: Task,
                                taskDefinition: TaskDefinition,
                                dao: SundialDao): Task = {
    task.status match {
      case s: CompletedTaskStatus => task
      case _ =>
        val newStatus = taskExecutor.refreshStatus(task)(dao) match {
          case Some(ExecutorStatus.Succeeded) =>
            if (taskDefinition.requireExplicitSuccess) {
              // check to see if the task reported itself as successful
              dao.processDao.findReportedTaskStatus(task.id) match {
                case Some(ReportedTaskStatus(_, status)) =>
                  val message =
                    s"The underlying executable has finished, and a status of $status was found; updating task."
                  dao.taskLogsDao.saveEvent(
                    TaskEventLog(UUID.randomUUID(),
                                 task.id,
                                 new Date(),
                                 EVENT_SOURCE_KEY,
                                 message)
                  )
                  dao.ensureCommitted()
                  status
                case _ =>
                  val message =
                    s"The underlying executable has finished, but no reported status was found; updating task as failed."
                  dao.taskLogsDao.saveEvent(
                    TaskEventLog(UUID.randomUUID(),
                                 task.id,
                                 new Date(),
                                 EVENT_SOURCE_KEY,
                                 message)
                  )
                  dao.ensureCommitted()
                  TaskStatus.Failure(new Date(), Some(message))
              }
            } else {
              TaskStatus.Success(new Date())
            }

          case Some(ExecutorStatus.Failed(reason)) =>
            val message =
              s"The underlying executable encountered a fault; updating task as failed."
            dao.taskLogsDao.saveEvent(
              TaskEventLog(UUID.randomUUID(),
                           task.id,
                           new Date(),
                           EVENT_SOURCE_KEY,
                           message)
            )
            dao.ensureCommitted()
            TaskStatus.Failure(new Date(), reason)

          case Some(ExecutorStatus.Initializing) => TaskStatus.Running()
          case Some(ExecutorStatus.Running)      => TaskStatus.Running()

          case Some(BatchExecutorStatus.Starting)   => TaskStatus.Running()
          case Some(BatchExecutorStatus.Runnable)   => TaskStatus.Running()
          case Some(BatchExecutorStatus.Pending)    => TaskStatus.Running()
          case Some(BatchExecutorStatus.Submitted)  => TaskStatus.Running()
          case Some(EmrExecutorState.CancelPending) => TaskStatus.Running()
          case Some(EmrExecutorState.Cancelled) =>
            TaskStatus.Failure(new Date(), Some("Emr Job Cancelled"))
          case Some(EmrExecutorState.Interrupted) =>
            TaskStatus.Failure(new Date(), Some("Emr Job Interrupted"))

          case None =>
            val message =
              s"The underlying executable was apparently never started; updating task as failed."
            dao.taskLogsDao.saveEvent(
              TaskEventLog(UUID.randomUUID(),
                           task.id,
                           new Date(),
                           EVENT_SOURCE_KEY,
                           message)
            )
            dao.ensureCommitted()
            TaskStatus.Failure(new Date(), Some("Task didn't start"))
        }

        if (task.status != newStatus) {
          val updated = dao.processDao.saveTask(task.copy(status = newStatus))
          dao.ensureCommitted()
          updated
        } else {
          task
        }
    }
  }

  class TaskContext(val allTasks: Seq[Task],
                    val allTaskDefinitions: Seq[TaskDefinition],
                    val process: Process,
                    val killed: Boolean)
      extends service.TaskContext {

    override def taskDefinitionForName(name: String): Option[TaskDefinition] = {
      allTaskDefinitions.find(_.name == name)
    }

    override def allConditions(
        taskDefinition: TaskDefinition): Map[String, TaskExecutionCondition] = {
      // for debugging purposes only
      SortedMap(
        "taskExecutionCondition" -> taskExecutionCondition(taskDefinition),
        "taskNotCurrentlyRunningExecutionCondition" -> taskNotCurrentlyRunningExecutionCondition(
          taskDefinition),
        "taskNotAlreadySuccessfulCondition" -> taskNotAlreadySuccessfulCondition(
          taskDefinition),
        "taskMaxAttemptsExecutionCondition" -> taskMaxAttemptsExecutionCondition(
          taskDefinition),
        "taskDependenciesExecutionCondition" -> taskDependenciesExecutionCondition(
          taskDefinition),
        "taskBackoffExecutionCondition" -> taskBackoffExecutionCondition(
          taskDefinition),
        "processNotKilledExecutionCondition" -> processNotKilledExecutionCondition()
      )
    }

    override def mostRecentStatusForTaskDefinition(
        taskDefinition: TaskDefinition): Option[TaskStatus] = {
      tasksForTaskDefinition(taskDefinition).headOption
        .map(_.status)
    }

    override def tasksForTaskDefinition(
        taskDefinition: TaskDefinition): Seq[Task] = {
      allTasks
        .filter(_.taskDefinitionName == taskDefinition.name)
        .sortBy(_.startedAt)
        .reverse
    }

    //
    // CONDITIONS
    //

    // Top-level condition: is the task allowed to run?
    override def taskExecutionCondition(
        taskDefinition: TaskDefinition): TaskExecutionCondition = {
      val conditions = Seq(
        taskNotAlreadySuccessfulCondition(taskDefinition),
        taskDependenciesExecutionCondition(taskDefinition),
        taskBackoffExecutionCondition(taskDefinition),
        taskMaxAttemptsExecutionCondition(taskDefinition),
        taskNotCurrentlyRunningExecutionCondition(taskDefinition),
        taskDependenciesExecutionCondition(taskDefinition),
        processNotKilledExecutionCondition()
      )
      conditions.reduce(TaskExecutionCondition.reduce)
    }

    // Is the task already running?
    override def taskNotCurrentlyRunningExecutionCondition(
        taskDefinition: TaskDefinition): TaskExecutionCondition = {
      mostRecentStatusForTaskDefinition(taskDefinition) match {
        case Some(TaskStatus.Running()) => TaskExecutionCondition.Pending
        case _                          => TaskExecutionCondition.Allowed
      }
    }

    // If the task has already succeeded, we don't need to run it again.
    override def taskNotAlreadySuccessfulCondition(
        taskDefinition: TaskDefinition): TaskExecutionCondition = {
      mostRecentStatusForTaskDefinition(taskDefinition) match {
        case Some(TaskStatus.Success(_)) =>
          TaskExecutionCondition.CompletedOrProhibited
        case _ => TaskExecutionCondition.Allowed
      }
    }

    // If the task has failed, has made more attempts than is allowed?
    override def taskMaxAttemptsExecutionCondition(
        taskDefinition: TaskDefinition): TaskExecutionCondition = {
      val attempts = tasksForTaskDefinition(taskDefinition).size
      val maxAttempts = taskDefinition.limits.maxAttempts
      if (attempts >= maxAttempts) {
        TaskExecutionCondition.CompletedOrProhibited
      } else {
        TaskExecutionCondition.Allowed
      }
    }

    // Is the task allowed to run based on its dependencies?
    // Required dependencies must have succeeded, while optional ones must have run at least once.
    // If a required dependency has completed as failed, this task will never be able to run.
    override def taskDependenciesExecutionCondition(
        taskDefinition: TaskDefinition): TaskExecutionCondition = {
      val dependencies = taskDefinition.dependencies
      // If there's no filter, include the dependency.  If there is a filter, only include if it's in the filter.
      def shouldIncludeTask(name: String) =
        process.taskFilter.map(_ contains name).getOrElse(true)
      val required = dependencies.required.filter(shouldIncludeTask)
      val optional = dependencies.optional.filter(shouldIncludeTask)
      val requiredConditions = required.flatMap(taskDefinitionForName).map {
        requiredDefinition =>
          mostRecentStatusForTaskDefinition(requiredDefinition) match {
            case Some(TaskStatus.Running())  => TaskExecutionCondition.Pending
            case Some(TaskStatus.Success(_)) => TaskExecutionCondition.Allowed
            case _                           =>
              // either it hasn't run yet, or it has failed
              taskExecutionCondition(requiredDefinition) match {
                case TaskExecutionCondition.Allowed =>
                  TaskExecutionCondition.Pending
                case TaskExecutionCondition.Pending =>
                  TaskExecutionCondition.Pending
                case TaskExecutionCondition.CompletedOrProhibited =>
                  TaskExecutionCondition.CompletedOrProhibited
              }
          }
      }
      val optionalConditions = optional.flatMap(taskDefinitionForName).map {
        optionalDefinition =>
          // must have completed (if failed, only one failure is required)
          mostRecentStatusForTaskDefinition(optionalDefinition) match {
            case Some(s: CompletedTaskStatus) => TaskExecutionCondition.Allowed
            case Some(_) =>
              TaskExecutionCondition.Pending // dependency is running
            case None =>
              TaskExecutionCondition.Pending // dependency hasn't tried to run yet
          }
      }
      (requiredConditions ++ optionalConditions)
        .reduceOption(TaskExecutionCondition.reduce)
        .getOrElse(TaskExecutionCondition.Allowed)
    }

    // If the task is awaiting retry, has enough time elapsed since its last failure?
    override def taskBackoffExecutionCondition(
        taskDefinition: TaskDefinition): TaskExecutionCondition = {
      // has it been enough time since the last run of this task?
      val attempts = tasksForTaskDefinition(taskDefinition)
      attempts.headOption match {
        case None =>
          // this task has never run before, so backoff isn't an issue
          TaskExecutionCondition.Allowed

        case Some(task) =>
          task.status match {
            case status: CompletedTaskStatus =>
              val millisSinceLastExecution = new Date().getTime - status.endedAt.getTime
              val numberOfAttempts = attempts.length
              val requiredMillis = TimeUnit.SECONDS.toMillis(
                taskDefinition.backoff.backoffTimeInSeconds(numberOfAttempts))
              if (millisSinceLastExecution > requiredMillis) {
                TaskExecutionCondition.Allowed
              } else {
                TaskExecutionCondition.Pending
              }
            case _ =>
              // the last execution of this task hasn't completed
              TaskExecutionCondition.Pending
          }
      }
    }

    override def processNotKilledExecutionCondition()
      : TaskExecutionCondition = {
      if (killed) {
        TaskExecutionCondition.CompletedOrProhibited
      } else {
        TaskExecutionCondition.Allowed
      }
    }
  }

}
