package dto

import java.util.{Date, UUID}

import model.{Task, TaskEventLog}

sealed trait TaskBackend

object TaskBackend {
  case object Batch extends TaskBackend {
    override val toString = "Batch"
  }
  case object Shell extends TaskBackend {
    override val toString = "Shell"
  }
  case object Emr extends TaskBackend {
    override val toString = "EMR"
  }
}

case class TaskDTO(
    name: String,
    finalId: Option[UUID],
    success: Boolean,
    attempts: Int,
    startedAt: Option[Date],
    endedAt: Option[Date],
    durationStr: Option[String],
    logs: Seq[TaskEventLog],
    tasks: Seq[Task],
    reason: Option[String],
    backend: TaskBackend
)
