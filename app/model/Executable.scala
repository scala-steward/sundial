package model

import java.util.{Date, UUID}

sealed trait TaskExecutorStatus {
  def isDone: Boolean
}

object TaskExecutorStatus {
  case object Initializing extends TaskExecutorStatus { override def isDone = false }
  case object Running extends TaskExecutorStatus { override def isDone = false }
  case object Completed extends TaskExecutorStatus { override def isDone = true } // includes task failures, as long as the container ended normally
  case class Fault(reason: Option[String]) extends TaskExecutorStatus { override def isDone = true }
}

sealed trait Executable
sealed trait ExecutableState {
  def asOf: Date
  def taskId: UUID
  def status: TaskExecutorStatus
}

case class ContainerServiceExecutable(image: String, tag: String = "latest", command: Seq[String], memory: Option[Int], cpu: Option[Int], taskRoleArn: Option[String], logPaths: Seq[String], environmentVariables: Map[String, String]) extends Executable
case class ContainerServiceState(taskId: UUID, asOf: Date, ecsTaskArn: String, status: TaskExecutorStatus) extends ExecutableState

case class ShellCommandExecutable(script: String, environmentVariables: Map[String, String]) extends Executable
case class ShellCommandState(taskId: UUID, asOf: Date, status: TaskExecutorStatus) extends ExecutableState
