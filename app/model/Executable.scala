package model

import java.util.{Date, UUID}

sealed trait ExecutorStatus {
  def isDone: Boolean
}

object ExecutorStatus {
  case object Initializing extends ExecutorStatus { override def isDone = false }
  case object Succeeded extends ExecutorStatus { override def isDone = true }
  case object Running extends ExecutorStatus { override def isDone = false }
  case class Failed(reason: Option[String]) extends ExecutorStatus { override def isDone = true }
}

object BatchExecutorStatus {
  case object Submitted extends ExecutorStatus { override def isDone = false }
  case object Runnable extends ExecutorStatus { override def isDone = false }
  case object Pending extends ExecutorStatus { override def isDone = false }
  case object Starting extends ExecutorStatus { override def isDone = false }

}

sealed trait Executable
sealed trait ExecutableState {
  def asOf: Date
  def taskId: UUID
  def status: ExecutorStatus
}

case class ECSExecutable(image: String, tag: String = "latest", command: Seq[String], memory: Option[Int], cpu: Option[Int], taskRoleArn: Option[String], logPaths: Seq[String], environmentVariables: Map[String, String]) extends Executable
case class ECSContainerState(taskId: UUID, asOf: Date, ecsTaskArn: String, status: ExecutorStatus) extends ExecutableState

case class BatchExecutable(image: String, tag: String = "latest", command: Seq[String], memory: Int, vCpus: Int, taskRoleArn: String, environmentVariables: Map[String, String], jobQueue: Option[String]) extends Executable
case class BatchContainerState(taskId: UUID, asOf: Date, jobName: String, jobId: UUID, status: ExecutorStatus) extends ExecutableState

case class ShellCommandExecutable(script: String, environmentVariables: Map[String, String]) extends Executable
case class ShellCommandState(taskId: UUID, asOf: Date, status: ExecutorStatus) extends ExecutableState
