package service

import javax.inject.Inject

import dao.{ExecutableStateDao, SundialDao}
import model._
import play.api.Logger

trait SpecificTaskExecutor[ExecutableType <: Executable, StateType <: ExecutableState] {

  protected def stateDao(implicit dao: SundialDao): ExecutableStateDao[StateType]

  def startExecutable(executable: ExecutableType, task: Task)(implicit dao: SundialDao): Unit = {
    val state = actuallyStartExecutable(executable, task)
    stateDao.saveState(state)
  }

  def killExecutable(task: Task, reason: String)(implicit dao: SundialDao): Unit = {
    stateDao.loadState(task.id).foreach { state =>
      actuallyKillExecutable(state, task, reason)
    }
  }

  def refreshStatus(task: Task)(implicit dao: SundialDao): Option[ExecutorStatus] = {
    Logger.debug(s"Refreshing task state for task $task")
    stateDao.loadState(task.id).map { state =>
      val newState = actuallyRefreshState(state)
      stateDao.saveState(newState)
      newState.status
    }
  }

  protected def actuallyStartExecutable(executable: ExecutableType, task: Task)(implicit dao: SundialDao): StateType
  protected def actuallyKillExecutable(state: StateType, task: Task, reason: String)(implicit dao: SundialDao): Unit
  protected def actuallyRefreshState(state: StateType)(implicit dao: SundialDao): StateType

}

class TaskExecutor @Inject() (
                               ecsContainerServiceExecutor: ECSServiceExecutor,
                               batchServiceExecutor: BatchServiceExecutor,
                               shellCommandExecutor: ShellCommandExecutor
) {

  def startExecutable(task: Task)(implicit dao: SundialDao): Unit = {
    task.executable match {
      case e: ECSExecutable => ecsContainerServiceExecutor.startExecutable(e, task)
      case e: BatchExecutable => batchServiceExecutor.startExecutable(e, task)
      case e: ShellCommandExecutable => shellCommandExecutor.startExecutable(e, task)
    }
  }

  def killExecutable(task: Task, reason: String)(implicit dao: SundialDao): Unit = {
    task.executable match {
      case e: ECSExecutable => ecsContainerServiceExecutor.killExecutable(task, reason)
      case e: BatchExecutable => batchServiceExecutor.killExecutable(task, reason)
      case e: ShellCommandExecutable => shellCommandExecutor.killExecutable(task, reason)
    }
  }

  def refreshStatus(task: Task)(implicit dao: SundialDao): Option[ExecutorStatus] = {
    Logger.debug(s"Refreshing status for task $task")
    task.executable match {
      case e: ECSExecutable => ecsContainerServiceExecutor.refreshStatus(task)
      case e: BatchExecutable => batchServiceExecutor.refreshStatus(task)
      case e: ShellCommandExecutable => shellCommandExecutor.refreshStatus(task)
    }
  }
}
