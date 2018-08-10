package dao.postgres.marshalling

import model.{EmrExecutorState, ExecutorStatus}

object PostgresEmrExecutorStatus {

  def apply(str: String): ExecutorStatus = str match {
    case "pending"        => ExecutorStatus.Initializing
    case "cancel_pending" => EmrExecutorState.CancelPending
    case "running"        => ExecutorStatus.Running
    case "completed"      => ExecutorStatus.Succeeded
    case "cancelled"      => EmrExecutorState.Cancelled
    case "failed"         => ExecutorStatus.Failed(None)
    case "interrupted"    => EmrExecutorState.Interrupted
    case _                => throw new IllegalArgumentException(s"Unexpected Status($str)")
  }

  def apply(status: ExecutorStatus) = status match {
    case ExecutorStatus.Initializing    => "pending"
    case EmrExecutorState.CancelPending => "cancel_pending"
    case ExecutorStatus.Running         => "running"
    case ExecutorStatus.Succeeded       => "completed"
    case EmrExecutorState.Cancelled     => "cancelled"
    case EmrExecutorState.Interrupted   => "interrupted"
    case ExecutorStatus.Failed(_)       => "failed"
    case status =>
      throw new IllegalArgumentException(s"Unexpected Status($status)")
  }

}
