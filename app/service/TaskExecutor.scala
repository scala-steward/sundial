package service

import dao.{SundialDao, ExecutableStateDao}
import model._
import play.api.Logger

trait SpecificTaskExecutor[ExecutableType <: Executable, StateType <: ExecutableState] {

  def stateDao(implicit dao: SundialDao): ExecutableStateDao[StateType]

  def startExecutable(executable: ExecutableType, task: Task)(implicit dao: SundialDao): Unit = {
    val state = actuallyStartExecutable(executable, task)
    stateDao.saveState(state)
  }

  def killExecutable(task: Task)(implicit dao: SundialDao): Unit = {
    stateDao.loadState(task.id).foreach { state =>
      actuallyKillExecutable(state, task)
    }
  }

  def refreshStatus(task: Task)(implicit dao: SundialDao): Option[TaskExecutorStatus] = {
    Logger.debug(s"Refreshing task state for task $task")
    stateDao.loadState(task.id).map { state =>
      val newState = actuallyRefreshState(state)
      stateDao.saveState(newState)
      newState.status
    }
  }

  protected def actuallyStartExecutable(executable: ExecutableType, task: Task)(implicit dao: SundialDao): StateType
  protected def actuallyKillExecutable(state: StateType, task: Task)(implicit dao: SundialDao): Unit
  protected def actuallyRefreshState(state: StateType)(implicit dao: SundialDao): StateType

}

class TaskExecutor(
  containerServiceExecutor: ContainerServiceExecutor,
  shellCommandExecutor: ShellCommandExecutor
) {

  def startExecutable(task: Task)(implicit dao: SundialDao): Unit = {
    task.executable match {
      case e: ContainerServiceExecutable => containerServiceExecutor.startExecutable(e, task)
      case e: ShellCommandExecutable => shellCommandExecutor.startExecutable(e, task)
    }
  }

  def killExecutable(task: Task)(implicit dao: SundialDao): Unit = {
    task.executable match {
      case e: ContainerServiceExecutable => containerServiceExecutor.killExecutable(task)
      case e: ShellCommandExecutable => shellCommandExecutor.killExecutable(task)
    }
  }

  def refreshStatus(task: Task)(implicit dao: SundialDao): Option[TaskExecutorStatus] = {
    Logger.debug(s"Refreshing status for task $task")
    task.executable match {
      case e: ContainerServiceExecutable => containerServiceExecutor.refreshStatus(task)
      case e: ShellCommandExecutable => shellCommandExecutor.refreshStatus(task)
    }
  }
}
