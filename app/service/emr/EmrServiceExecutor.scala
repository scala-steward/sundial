package service.emr

import java.util
import java.util.Date

import com.hbc.svc.sundial.v2.models.EmrConfiguration
import dao.{ExecutableStateDao, SundialDao}
import javax.inject.Inject
import model._
import play.api.Logging
import service.SpecificTaskExecutor
import software.amazon.awssdk.services.emr.EmrClient
import software.amazon.awssdk.services.emr.model.{
  ActionOnFailure,
  AddJobFlowStepsRequest,
  Application,
  CancelStepsRequest,
  Configuration,
  EbsBlockDeviceConfig,
  EbsConfiguration,
  InstanceGroupConfig,
  InstanceRoleType,
  JobFlowInstancesConfig,
  ListStepsRequest,
  RunJobFlowRequest,
  TerminateJobFlowsRequest,
  VolumeSpecification
}

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

class EmrServiceExecutor @Inject()(emrClient: EmrClient)
    extends SpecificTaskExecutor[EmrJobExecutable, EmrJobState]
    with Logging {

  private val emrStateHelper = EmrStepHelper()

  override protected def stateDao(
      implicit dao: SundialDao): ExecutableStateDao[EmrJobState] =
    dao.emrJobStateDao

  override protected[emr] def actuallyStartExecutable(
      executable: EmrJobExecutable,
      task: Task)(implicit dao: SundialDao): EmrJobState = {

    if (executable.emrClusterDetails.existingCluster) {
      submitJobToExistingCluster(executable, task)
    } else {
      createClusterAndSubmitJob(executable, task)
    }

  }

  private def submitJobToExistingCluster(executable: EmrJobExecutable,
                                         task: Task): EmrJobState = {

    val clusterId = executable.emrClusterDetails.clusterId.get

    val args = emrStateHelper.buildSparkArgs(executable)

    val loadDataJobs = emrStateHelper.toStepConfig(executable.loadData)
    val saveResultsJobs =
      emrStateHelper.toStepConfig(executable.saveResults)

    val steps = loadDataJobs ++
      List(
        emrStateHelper.buildStepConfig(executable.jobName,
                                       args,
                                       ActionOnFailure.CONTINUE)) ++
      saveResultsJobs

    val (stepIds, executorStatus) = try {
      val jobRequest = AddJobFlowStepsRequest
        .builder()
        .jobFlowId(clusterId)
        .steps(steps: _*)
        .build()
      val stepIds: Seq[String] = emrClient
        .addJobFlowSteps(jobRequest)
        .stepIds()
        .asScala
      (stepIds, ExecutorStatus.Initializing)
    } catch {
      case NonFatal(t) =>
        (List("N/A"), ExecutorStatus.Failed(Some(t.getMessage)))
    }

    EmrJobState(task.id,
                executable.jobName,
                clusterId,
                stepIds,
                executable.region,
                new Date(),
                executorStatus)

  }

  private def createClusterAndSubmitJob(executable: EmrJobExecutable,
                                        task: Task): EmrJobState = {

    def toConfigurations(emrConfigsOpt: Option[Seq[EmrConfiguration]])
      : util.Collection[Configuration] = {
      emrConfigsOpt.map { emrConfigs =>
        emrConfigs.map(toConfiguration).asJavaCollection
      }.orNull
    }

    def toConfiguration(emrConfig: EmrConfiguration): Configuration = {
      val properties = emrConfig.properties.map(_.asJava).orNull
      Configuration
        .builder()
        .classification(emrConfig.classification.orNull)
        .configurations(toConfigurations(emrConfig.configurations))
        .properties(properties)
        .build()
    }

    /**
      * Builds an AWS InstanceGroupConfig from the given job's configuration
      *
      * @param instanceRoleType
      * @param instanceGroupDetails
      * @return
      */
    def toInstanceGroupConfig(instanceRoleType: InstanceRoleType,
                              instanceGroupDetails: InstanceGroupDetails) = {
      var instanceGroupConfigBuilder = InstanceGroupConfig
        .builder()
        .instanceRole(instanceRoleType)
        .market(instanceGroupDetails.awsMarket.toUpperCase)
        .instanceType(instanceGroupDetails.instanceType)
        .instanceCount(instanceGroupDetails.instanceCount)

      // Set the price for SPOT instances
      instanceGroupConfigBuilder = instanceGroupDetails.bidPriceOpt
        .fold(instanceGroupConfigBuilder)(bidPrice =>
          instanceGroupConfigBuilder.bidPrice(bidPrice.toString))

      // If ebs volume is set, a single GP2 disk is attached to the instance.
      instanceGroupConfigBuilder = instanceGroupDetails.ebsVolumeSizeOpt
        .fold(instanceGroupConfigBuilder)(ebsVolumeSize => {
          instanceGroupConfigBuilder
            .ebsConfiguration(
              EbsConfiguration
                .builder()
                .ebsBlockDeviceConfigs(
                  EbsBlockDeviceConfig
                    .builder()
                    .volumesPerInstance(1)
                    .volumeSpecification(
                      VolumeSpecification
                        .builder()
                        .sizeInGB(ebsVolumeSize)
                        .volumeType("gp2")
                        .build())
                    .build())
                .ebsOptimized(true)
                .build())
        })

      instanceGroupConfigBuilder.build()
    }

    val clusterDetails = executable.emrClusterDetails

    val emrJobStateEither = for {
      clusterName <- clusterDetails.clusterName.toRight("Cluster name missing")
      releaseLabel <- clusterDetails.releaseLabel.toRight(
        "Release label missing")
      logUri <- clusterDetails.s3LogUri.toRight("s3 log URI missing")
      emrServiceRole <- clusterDetails.emrServiceRole.toRight(
        "EMR service role missing")
      emrJobFlowRole <- clusterDetails.emrJobFlowRole.toRight(
        "EMR job flow role missing")
      masterInstanceGroup <- clusterDetails.masterInstanceGroup.toRight(
        "Master instance group missing")
      isVisibleToAllUsers <- clusterDetails.visibleToAllUsers.toRight(
        "Visible to all users flag missing")
    } yield {

      // List of applications to install on the new EMR cluster
      val applications =
        clusterDetails.applications.map(Application.builder().name(_).build())

      val args = emrStateHelper.buildSparkArgs(executable)

      // Master, Core and Task instance groups
      val instanceDetails = List(
        Some(
          toInstanceGroupConfig(InstanceRoleType.MASTER, masterInstanceGroup)),
        clusterDetails.coreInstanceGroup.map(instanceDetails =>
          toInstanceGroupConfig(InstanceRoleType.CORE, instanceDetails)),
        clusterDetails.taskInstanceGroup.map(instanceDetails =>
          toInstanceGroupConfig(InstanceRoleType.TASK, instanceDetails))
      ).flatten.asJavaCollection

      // Configuration of the EMR instances
      val jobFlowInstancesConfigBuilder = JobFlowInstancesConfig
        .builder()
        .keepJobFlowAliveWhenNoSteps(false)
        .instanceGroups(instanceDetails)

      // Add Ec2Subnet if subnet defined in configuration
      val jobFlowInstancesConfig = executable.emrClusterDetails.ec2Subnet
        .fold(jobFlowInstancesConfigBuilder)(
          jobFlowInstancesConfigBuilder.ec2SubnetId(_))
        .build()

      val loadDataSteps = emrStateHelper.toStepConfig(executable.loadData)
      val saveResultSteps =
        emrStateHelper.toStepConfig(executable.saveResults)

      val stepConfigs = loadDataSteps ++
        List(emrStateHelper.buildStepConfig(executable.jobName, args)) ++
        saveResultSteps

      // The actual cluster to launch
      val request = RunJobFlowRequest
        .builder()
        .name(clusterName)
        .releaseLabel(releaseLabel)
        .steps(stepConfigs: _*)
        .applications(applications: _*)
        .logUri(logUri)
        .serviceRole(emrServiceRole)
        .jobFlowRole(emrJobFlowRole)
        .instances(jobFlowInstancesConfig)
        .visibleToAllUsers(isVisibleToAllUsers)
        .configurations(toConfigurations(
          executable.emrClusterDetails.configurations))
        .securityConfiguration(
          executable.emrClusterDetails.securityConfiguration.orNull)
        .build()

      // aka the cluster id
      try {
        val flowId = emrClient.runJobFlow(request).jobFlowId()

        val steps = emrClient
          .listSteps(ListStepsRequest.builder().clusterId(flowId).build())
          .steps()
          .asScala

        val jobState = EmrJobState(
          task.id,
          executable.jobName,
          flowId,
          steps.map(_.id()),
          executable.region,
          new Date(),
          emrStateHelper.getOverallExecutorState(steps.map(_.status().state()))
        )

        logger.info(s"Submitted $jobState to cluster")
        jobState

      } catch {
        case NonFatal(e) =>
          logger.error("Exception creating EMR cluster", e)
          EmrJobState(
            task.id,
            executable.jobName,
            "N/A",
            List("N/A"),
            executable.region,
            new Date(),
            ExecutorStatus.Failed(
              Some(s"Could not create new EMR cluster due to ${e.getMessage}")
            )
          )
      }

    }

    // This is sub-optimal (check for-comp on Option), but if for whichever reason the configuration is broken, this won't start any cluster and fail the job
    emrJobStateEither.fold(
      errorMessage => {
        logger.error(
          s"Could not create new EMR cluster ${executable.jobName} due to $errorMessage")
        EmrJobState(
          task.id,
          executable.jobName,
          "N/A",
          List("N/A"),
          executable.region,
          new Date(),
          ExecutorStatus.Failed(
            Some(s"Could not create new EMR cluster due to $errorMessage")
          )
        )
      },
      identity
    )

  }

  /**
    * Attempts to kill an EMR Step ie. a Sundial EMR job.
    *
    * Please note that is NOT possible to Kill a Step running on EMR unless the step itself is in Pending.
    *
    * For steps running on _new_ Emr Clusters, the cluster is killed instead.
    *
    * Check here: https://aws.amazon.com/premiumsupport/knowledge-center/cancel-emr-step/ for more details.
    *
    * @param state
    * @param task
    * @param reason
    * @param dao
    */
  override protected def actuallyKillExecutable(
      state: EmrJobState,
      task: Task,
      reason: String)(implicit dao: SundialDao): Unit = {

    val emrJobExecutable = task.executable.asInstanceOf[EmrJobExecutable]

    if (emrJobExecutable.emrClusterDetails.existingCluster) {

      val cancelStepsRequest = CancelStepsRequest
        .builder()
        .clusterId(state.clusterId)
        .stepIds(state.stepIds: _*)
        .build()
      val response = emrClient.cancelSteps(cancelStepsRequest)
      val stepCancelResponse =
        response.cancelStepsInfoList.asScala.head

      logger.info(s"stepCancelResponse - Reason: ${stepCancelResponse
        .reason()}, Status: ${stepCancelResponse.status()}")

    } else {
      val terminateJobFlowsRequest =
        TerminateJobFlowsRequest.builder().jobFlowIds(state.clusterId).build()
      emrClient.terminateJobFlows(terminateJobFlowsRequest)
    }

  }

  override protected def actuallyRefreshState(state: EmrJobState)(
      implicit dao: SundialDao): EmrJobState = {

    val listStepsRequest = ListStepsRequest
      .builder()
      .clusterId(state.clusterId)
      .stepIds(state.stepIds: _*)
      .build()
    try {
      val statuses = emrClient
        .listSteps(listStepsRequest)
        .steps()
        .asScala
        .map(_.status().state())
      state.copy(status = emrStateHelper.getOverallExecutorState(statuses))
    } catch {
      case NonFatal(t) => {
        logger.error(s"Could not refresh State($state)", t)
        state.copy(status = ExecutorStatus.Failed(Some(t.getMessage)))
      }
    }

  }

}
