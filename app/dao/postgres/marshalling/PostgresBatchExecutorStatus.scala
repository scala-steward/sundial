package dao.postgres.marshalling

import model.{BatchExecutorStatus, ExecutorStatus}

object PostgresBatchExecutorStatus {

  def apply(str: String): ExecutorStatus = str match {
    case "initializing" => ExecutorStatus.Initializing
    case "submitted"    => BatchExecutorStatus.Submitted
    case "runnable"     => BatchExecutorStatus.Runnable
    case "pending"      => BatchExecutorStatus.Pending
    case "starting"     => BatchExecutorStatus.Starting
    case "running"      => ExecutorStatus.Running
    case "succeeded"    => ExecutorStatus.Succeeded
    case "failed"       => ExecutorStatus.Failed(None)
  }

  def apply(status: ExecutorStatus) = status match {
    case ExecutorStatus.Initializing   => "initializing"
    case BatchExecutorStatus.Submitted => "submitted"
    case BatchExecutorStatus.Pending   => "pending"
    case BatchExecutorStatus.Runnable  => "runnable"
    case BatchExecutorStatus.Starting  => "starting"
    case ExecutorStatus.Running        => "running"
    case ExecutorStatus.Succeeded      => "succeeded"
    case ExecutorStatus.Failed(_)      => "failed"
  }

}
