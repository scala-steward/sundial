package dao.postgres.marshalling

import model.ExecutorStatus

object PostgresECSExecutorStatus {

  def apply(str: String): ExecutorStatus = str match {
    case "initializing" => ExecutorStatus.Initializing
    case "running" => ExecutorStatus.Running
    case "completed" => ExecutorStatus.Succeeded
    case "fault" => ExecutorStatus.Failed(None)
  }

  def apply(status: ExecutorStatus) = status match {
    case ExecutorStatus.Initializing => "initializing"
    case ExecutorStatus.Running => "running"
    case ExecutorStatus.Succeeded => "completed"
    case  ExecutorStatus.Failed(_) => "fault"
  }

}
