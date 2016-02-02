package model

import java.util.{Date, UUID}

sealed trait TaskStatus {
  def isComplete: Boolean = false
  def statusType: TaskStatusType
}
sealed trait CompletedTaskStatus extends TaskStatus {
  override def isComplete: Boolean = true
  def endedAt: Date
}

object TaskStatus {
  case class Success(endedAt: Date) extends CompletedTaskStatus {
    override def statusType = TaskStatusType.Success
    override def toString = "Success"
  }
  case class Failure(endedAt: Date, reason: Option[String]) extends CompletedTaskStatus {
    override def statusType = TaskStatusType.Failure
    override def toString = "Failure, reason: " + reason.getOrElse("Unknown")
  }
  case class Running() extends TaskStatus {
    override def statusType = TaskStatusType.Running
    override def toString = "Running"
  }
}

sealed trait TaskStatusType // used for searching/filtering

object TaskStatusType {
  case object Success extends TaskStatusType
  case object Failure extends TaskStatusType
  case object Running extends TaskStatusType
}

case class Task(
  id: UUID,
  processId: UUID,
  processDefinitionName: String,
  taskDefinitionName: String,
  // we store the executable here so that if the underlying task definition changes,
  // we have a record of the original executable that this task ran with
  executable: Executable,
  previousAttempts: Int,
  startedAt: Date,
  status: TaskStatus
) {

  def endedAt: Option[Date] = status match {
    case s: CompletedTaskStatus => Some(s.endedAt)
    case _ => None
  }

}
