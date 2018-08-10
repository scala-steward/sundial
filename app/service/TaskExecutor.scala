package service

import javax.inject.Inject

import dao.{ExecutableStateDao, SundialDao}
import model._
import play.api.{Application, Configuration, Logger}

trait SpecificTaskExecutor[
    ExecutableType <: Executable, StateType <: ExecutableState] {

  protected def stateDao(
      implicit dao: SundialDao): ExecutableStateDao[StateType]

  def startExecutable(executable: ExecutableType, task: Task)(
      implicit dao: SundialDao): Unit = {
    val state = actuallyStartExecutable(executable, task)
    stateDao.saveState(state)
  }

  def killExecutable(task: Task, reason: String)(
      implicit dao: SundialDao): Unit = {
    stateDao.loadState(task.id).foreach { state =>
      actuallyKillExecutable(state, task, reason)
    }
  }

  def refreshStatus(task: Task)(
      implicit dao: SundialDao): Option[ExecutorStatus] = {
    Logger.debug(s"Refreshing task state for task $task")
    stateDao.loadState(task.id).map { state =>
      val newState = actuallyRefreshState(state)
      stateDao.saveState(newState)
      newState.status
    }
  }

  protected def actuallyStartExecutable(executable: ExecutableType, task: Task)(
      implicit dao: SundialDao): StateType

  protected def actuallyKillExecutable(
      state: StateType,
      task: Task,
      reason: String)(implicit dao: SundialDao): Unit

  protected def actuallyRefreshState(state: StateType)(
      implicit dao: SundialDao): StateType

}

class TaskExecutor @Inject()(batchServiceExecutor: BatchServiceExecutor,
                             shellCommandExecutor: ShellCommandExecutor,
                             emrServiceExecutor: EmrServiceExecutor)(
    implicit configuration: Configuration,
    application: Application) {

  // Not perfect design here, but because of the deprecation it will be enough to just delete line below and update cases accordingly.
  private val ecsContainerServiceExecutorOpt: Option[ECSServiceExecutor] =
    configuration
      .getOptional[String]("ecs.cluster")
      .fold(Option.empty[ECSServiceExecutor])(_ =>
        Some(application.injector.instanceOf(classOf[ECSServiceExecutor])))

  def startExecutable(task: Task)(implicit dao: SundialDao): Unit = {
    task.executable match {
      case e: ECSExecutable if ecsContainerServiceExecutorOpt.isDefined =>
        ecsContainerServiceExecutorOpt.get.startExecutable(e, task)
      case e: BatchExecutable => batchServiceExecutor.startExecutable(e, task)
      case e: ShellCommandExecutable =>
        shellCommandExecutor.startExecutable(e, task)
      case e: EmrJobExecutable => emrServiceExecutor.startExecutable(e, task)
      case _                   => Logger.warn(s"No Executor found for Task($task)")
    }
  }

  def killExecutable(task: Task, reason: String)(
      implicit dao: SundialDao): Unit = {
    task.executable match {
      case _: ECSExecutable if ecsContainerServiceExecutorOpt.isDefined =>
        ecsContainerServiceExecutorOpt.get.killExecutable(task, reason)
      case _: BatchExecutable =>
        batchServiceExecutor.killExecutable(task, reason)
      case _: ShellCommandExecutable =>
        shellCommandExecutor.killExecutable(task, reason)
      case _: EmrJobExecutable =>
        emrServiceExecutor.killExecutable(task, reason)
      case _ => Logger.warn(s"No Executor found for Task($task)")
    }
  }

  def refreshStatus(task: Task)(
      implicit dao: SundialDao): Option[ExecutorStatus] = {
    Logger.debug(s"Refreshing status for task $task")
    task.executable match {
      case _: ECSExecutable if ecsContainerServiceExecutorOpt.isDefined =>
        ecsContainerServiceExecutorOpt.get.refreshStatus(task)
      case _: BatchExecutable        => batchServiceExecutor.refreshStatus(task)
      case _: ShellCommandExecutable => shellCommandExecutor.refreshStatus(task)
      case _: EmrJobExecutable       => emrServiceExecutor.refreshStatus(task)
      case _ => {
        Logger.warn(s"No Executor found for Task($task)")
        None
      }
    }
  }
}
