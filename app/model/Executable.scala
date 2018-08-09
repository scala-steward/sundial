package model

import java.util.{Date, UUID}

import com.hbc.svc.sundial.v1.models.EmrConfiguration

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

object EmrExecutorState {
  case object CancelPending extends ExecutorStatus { override def isDone: Boolean = false }
  case object Cancelled extends ExecutorStatus { override def isDone: Boolean = true }
  case object Interrupted extends ExecutorStatus { override def isDone: Boolean = true }
}

sealed trait Executable
sealed trait ExecutableState {
  def asOf: Date
  def taskId: UUID
  def status: ExecutorStatus
}

case class ECSExecutable(image: String, tag: String = "latest", command: Seq[String], memory: Option[Int], cpu: Option[Int], taskRoleArn: Option[String], logPaths: Seq[String], environmentVariables: Map[String, String]) extends Executable
case class ECSContainerState(taskId: UUID, asOf: Date, ecsTaskArn: String, status: ExecutorStatus) extends ExecutableState

case class BatchExecutable(image: String, tag: String = "latest", command: Seq[String], memory: Int, vCpus: Int, jobRoleArn: Option[String], environmentVariables: Map[String, String], jobQueue: Option[String]) extends Executable
case class BatchContainerState(taskId: UUID, asOf: Date, jobName: String, jobId: UUID, logStreamName: Option[String], status: ExecutorStatus) extends ExecutableState

case class ShellCommandExecutable(script: String, environmentVariables: Map[String, String]) extends Executable
case class ShellCommandState(taskId: UUID, asOf: Date, status: ExecutorStatus) extends ExecutableState

case class EmrClusterDetails(clusterName: Option[String],
                             clusterId: Option[String],
                             releaseLabel: Option[String] = None,
                             applications: Seq[String] = Seq.empty,
                             s3LogUri: Option[String] = None,
                             masterInstanceGroup: Option[InstanceGroupDetails] = None,
                             coreInstanceGroup: Option[InstanceGroupDetails] = None,
                             taskInstanceGroup: Option[InstanceGroupDetails] = None,
                             ec2Subnet: Option[String] = None,
                             emrServiceRole: Option[String] = None,
                             emrJobFlowRole: Option[String] = None,
                             visibleToAllUsers: Option[Boolean] = None,
                             configurations: Option[Seq[EmrConfiguration]] = None,
                             existingCluster: Boolean,
                             securityConfiguration: Option[String] = None)

case class LogDetails(logGroupName: String, logStreamName: String)

case class InstanceGroupDetails(instanceType: String, instanceCount: Int, awsMarket: String, bidPriceOpt: Option[Double], ebsVolumeSizeOpt: Option[Int])

case class CopyFileJob(source: String, destination: String)

case class EmrJobExecutable(emrClusterDetails: EmrClusterDetails,
                            jobName: String,
                            region: String,
                            clazz: String,
                            s3JarPath: String,
                            sparkConf: Seq[String],
                            args: Seq[String],
                            s3LogDetailsOpt: Option[LogDetails],
                            loadData: Option[Seq[CopyFileJob]],
                            saveResults: Option[Seq[CopyFileJob]]) extends Executable

case class EmrJobState(taskId: UUID, jobName: String, clusterId: String, stepIds: Seq[String], region: String, asOf: Date, status: ExecutorStatus) extends ExecutableState
