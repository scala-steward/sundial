package service

import java.util.Date
import javax.inject.Inject

import com.amazonaws.regions.Regions
import com.amazonaws.services.elasticmapreduce.model.{ActionOnFailure, AddJobFlowStepsRequest, Application, CancelStepsRequest, DescribeClusterRequest, HadoopJarStepConfig, InstanceGroupConfig, InstanceRoleType, JobFlowInstancesConfig, ListStepsRequest, MarketType, RunJobFlowRequest, StepConfig, TerminateJobFlowsRequest}
import com.amazonaws.services.elasticmapreduce.{AmazonElasticMapReduce, AmazonElasticMapReduceClientBuilder}
import com.jcraft.jsch.{ChannelExec, JSch}
import dao.{ExecutableStateDao, SundialDao}
import model._
import play.api.Logger

import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.io.Source
import scala.util.control.NonFatal

class EmrServiceExecutor @Inject()() extends SpecificTaskExecutor[EmrJobExecutable, EmrJobState] {

  private val CommandRunnerJar = "command-runner.jar"

  private val SparkSubmitCommand = "spark-submit"

  private val ConfOption = "--conf"

  private val ClassOption = "--class"

  override protected def stateDao(implicit dao: SundialDao): ExecutableStateDao[EmrJobState] = dao.emrJobStateDao

  override protected def actuallyStartExecutable(executable: EmrJobExecutable, task: Task)(implicit dao: SundialDao): EmrJobState = {

    if (executable.emrClusterDetails.existingCluster) {
      submitJobToExistingCluster(executable, task)
    } else {
      createClusterAndSubmitJob(executable, task)
    }

  }

  private def submitJobToExistingCluster(executable: EmrJobExecutable, task: Task) = {

    val emrClient = getEmrClient(executable.region)

    val clusterId = executable.emrClusterDetails.clusterId.get

    val sparkConfigs = executable
      .sparkConf
      .flatMap(conf => List(ConfOption, conf))

    val args = List(SparkSubmitCommand) ++
      sparkConfigs ++
      List(
        ClassOption, executable.clazz,
        executable.s3JarPath) ++
      executable.args

    val (stepId, executorStatus) = try {
      val jobRequest = new AddJobFlowStepsRequest(clusterId)
        .withSteps(
          List(
            new StepConfig()
              .withActionOnFailure(ActionOnFailure.CONTINUE)
              .withName(executable.jobName)
              .withHadoopJarStep(
                new HadoopJarStepConfig(CommandRunnerJar)
                  .withArgs(args: _*)
              )
          ): _*
        )
      val stepId = emrClient
        .addJobFlowSteps(jobRequest)
        .getStepIds
        .asScala
        .head
      (stepId, ExecutorStatus.Initializing)
    } catch {
      case NonFatal(t) => ("N/A", ExecutorStatus.Failed(Some(t.getMessage)))
    }

    EmrJobState(task.id,
      executable.jobName,
      clusterId,
      stepId,
      executable.region,
      new Date(),
      executorStatus)

  }

  private def createClusterAndSubmitJob(executable: EmrJobExecutable, task: Task) = {

    def toInstanceGroupConfig(instanceRoleType: InstanceRoleType, instanceGroupDetails: InstanceGroupDetails) = {
      val instanceGroupConfig = new InstanceGroupConfig()
        .withInstanceRole(instanceRoleType)
        .withMarket(instanceGroupDetails.awsMarket.toUpperCase)
        .withInstanceType(instanceGroupDetails.instanceType)
        .withInstanceCount(instanceGroupDetails.instanceCount)

      instanceGroupDetails
        .bidPriceOpt
        .fold(instanceGroupConfig)(bidPrice => instanceGroupConfig.withBidPrice(bidPrice.toString))
    }

    val emrClient = getEmrClient(executable.region)

    val clusterDetails = executable.emrClusterDetails

    val emrJobStateOpt = for {
      clusterName <- clusterDetails.clusterName
      releaseLabel <- clusterDetails.releaseLabel
      logUri <- clusterDetails.s3LogUri
      emrServiceRole <- clusterDetails.emrServiceRole
      emrJobFlowRole <- clusterDetails.emrJobFlowRole
      masterInstanceGroup <- clusterDetails.masterInstanceGroup

    } yield {

      val applications = clusterDetails.applications.map(new Application().withName(_))

      val sparkConfigs = executable
        .sparkConf
        .flatMap(conf => List(ConfOption, conf))

      val args = List(SparkSubmitCommand) ++
        sparkConfigs ++
        List(
          ClassOption, executable.clazz,
          executable.s3JarPath) ++
        executable.args

      val instanceDetails = List (
        Some(toInstanceGroupConfig(InstanceRoleType.MASTER, masterInstanceGroup)),
        clusterDetails.coreInstanceGroup.map(instanceDetails => toInstanceGroupConfig(InstanceRoleType.CORE, instanceDetails)),
        clusterDetails.taskInstanceGroup.map(instanceDetails => toInstanceGroupConfig(InstanceRoleType.TASK, instanceDetails))
      ).flatten

      var jobFlowInstancesConfig = new JobFlowInstancesConfig()
        .withKeepJobFlowAliveWhenNoSteps(false)
        .withInstanceGroups(instanceDetails: _*)

      // Add Ec2Subnet if subnet defined in configuration
      jobFlowInstancesConfig = executable.emrClusterDetails.ec2Subnet.fold(jobFlowInstancesConfig)(jobFlowInstancesConfig.withEc2SubnetId(_))

      val request = new RunJobFlowRequest()
        .withName(clusterName)
        .withReleaseLabel(releaseLabel)
        .withSteps(new StepConfig()
          .withName(executable.jobName)
          .withActionOnFailure(ActionOnFailure.TERMINATE_CLUSTER)
          .withHadoopJarStep(
            new HadoopJarStepConfig(CommandRunnerJar)
              .withArgs(args: _*)
          ))
        .withApplications(applications: _*)
        .withLogUri(logUri)
        .withServiceRole(emrServiceRole)
        .withJobFlowRole(emrJobFlowRole)
        .withInstances(jobFlowInstancesConfig)

      val flowId = emrClient.runJobFlow(request).getJobFlowId

      Logger.info(s"FlowId($flowId)")

      val step = emrClient
        .listSteps(new ListStepsRequest().withClusterId(flowId))
        .getSteps
        .asScala
        .find(_.getName == executable.jobName)
        .get


      EmrJobState(task.id,
        executable.jobName,
        flowId,
        step.getId,
        executable.region,
        new Date(),
        getExecutorState(step.getStatus.getState))

    }

    emrJobStateOpt.getOrElse(
      EmrJobState(task.id,
        executable.jobName,
        "N/A",
        "N/A",
        executable.region,
        new Date(),
        ExecutorStatus.Failed(Some("Could not create new EMR cluster, verify configuration")))
    )

  }

  override protected def actuallyKillExecutable(state: EmrJobState, task: Task, reason: String)(implicit dao: SundialDao): Unit = {

    val emrClient = getEmrClient(state.region)
    val emrJobExecutable = task.executable.asInstanceOf[EmrJobExecutable]

    if (emrJobExecutable.emrClusterDetails.existingCluster) {

      val cancelStepsRequest = new CancelStepsRequest().withClusterId(state.clusterId).withStepIds(state.stepId)
      val response = emrClient.cancelSteps(cancelStepsRequest)
      val stepCancelResponse = response
        .getCancelStepsInfoList
        .asScala
        .head

      Logger.info(s"stepCancelResponse - Reason: ${stepCancelResponse.getReason}, Status: ${stepCancelResponse.getStatus}")

    } else {
      val terminateJobFlowsRequest = new TerminateJobFlowsRequest().withJobFlowIds(state.clusterId)
      emrClient.terminateJobFlows(terminateJobFlowsRequest)
    }

  }

  private def getExecutorState(state: String): ExecutorStatus = {
    state match {
      case "PENDING" => ExecutorStatus.Initializing
      case "CANCEL_PENDING" => EmrExecutorState.CancelPending
      case "RUNNING" => ExecutorStatus.Running
      case "COMPLETED" => ExecutorStatus.Succeeded
      case "CANCELLED" => EmrExecutorState.Cancelled
      case "FAILED" => ExecutorStatus.Failed(None)
      case "INTERRUPTED" => EmrExecutorState.Interrupted
    }
  }

  override protected def actuallyRefreshState(state: EmrJobState)(implicit dao: SundialDao): EmrJobState = {
    val emrClient = getEmrClient(state.region)

    val listStepsRequest = new ListStepsRequest().withClusterId(state.clusterId).withStepIds(state.stepId)
    try {
      val status = emrClient.listSteps(listStepsRequest).getSteps.asScala.head.getStatus
      state.copy(status = getExecutorState(status.getState))
    } catch {
      case NonFatal(t) => {
        Logger.error(s"Could not refresh State($state)", t)
        state.copy(status = ExecutorStatus.Failed(Some(t.getMessage)))
      }
    }
  }

  private def getEmrClient(region: String): AmazonElasticMapReduce =
    AmazonElasticMapReduceClientBuilder
      .standard()
      .withRegion(Regions.fromName(region))
      .build()

}