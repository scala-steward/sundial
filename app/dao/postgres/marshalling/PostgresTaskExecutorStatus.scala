package dao.postgres.marshalling

import model.TaskExecutorStatus

object PostgresTaskExecutorStatus {

  def apply(str: String): TaskExecutorStatus = str match {
    case "initializing" => TaskExecutorStatus.Initializing
    case "running" => TaskExecutorStatus.Running
    case "completed" => TaskExecutorStatus.Completed
    case "fault" => TaskExecutorStatus.Fault(None)
  }

  def apply(status: TaskExecutorStatus) = status match {
    case TaskExecutorStatus.Initializing => "initializing"
    case TaskExecutorStatus.Running => "running"
    case TaskExecutorStatus.Completed => "completed"
    case TaskExecutorStatus.Fault(_) => "fault"
  }

}
