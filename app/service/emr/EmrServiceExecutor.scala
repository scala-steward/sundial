package service.emr

import java.util
import java.util.Date

import cats.effect.IO
import com.amazonaws.regions.Regions
import com.amazonaws.services.elasticmapreduce.model.{
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
import com.hbc.svc.sundial.v1.models.EmrConfiguration
import dao.{ExecutableStateDao, SundialDao}
import javax.inject.Inject
import model._
import play.api.Logger
import service.SpecificTaskExecutor

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

class EmrServiceExecutor @Inject()(emrClientFactory: EmrClientFactory)
    extends SpecificTaskExecutor[EmrJobExecutable, EmrJobState] {

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

    emrClientFactory
      .emrClientResource(Regions.fromName(executable.region))
      .use { emrClient =>
        IO {

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
            val jobRequest = new AddJobFlowStepsRequest(clusterId)
              .withSteps(steps: _*)
            val stepIds: Seq[String] = emrClient
              .addJobFlowSteps(jobRequest)
              .getStepIds
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
      }
      .unsafeRunSync()

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
      new Configuration()
        .withClassification(emrConfig.classification.orNull)
        .withConfigurations(toConfigurations(emrConfig.configurations))
        .withProperties(properties)
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
      var instanceGroupConfig = new InstanceGroupConfig()
        .withInstanceRole(instanceRoleType)
        .withMarket(instanceGroupDetails.awsMarket.toUpperCase)
        .withInstanceType(instanceGroupDetails.instanceType)
        .withInstanceCount(instanceGroupDetails.instanceCount)

      // Set the price for SPOT instances
      instanceGroupConfig = instanceGroupDetails.bidPriceOpt
        .fold(instanceGroupConfig)(bidPrice =>
          instanceGroupConfig.withBidPrice(bidPrice.toString))

      // If ebs volume is set, a single GP2 disk is attached to the instance.
      instanceGroupConfig = instanceGroupDetails.ebsVolumeSizeOpt
        .fold(instanceGroupConfig)(ebsVolumeSize => {
          instanceGroupConfig
            .withEbsConfiguration(
              new EbsConfiguration()
                .withEbsBlockDeviceConfigs(
                  new EbsBlockDeviceConfig()
                    .withVolumesPerInstance(1)
                    .withVolumeSpecification(new VolumeSpecification()
                      .withSizeInGB(ebsVolumeSize)
                      .withVolumeType("gp2")))
                .withEbsOptimized(true))
        })

      instanceGroupConfig
    }

    emrClientFactory
      .emrClientResource(Regions.fromName(executable.region))
      .use { emrClient =>
        IO {

          val clusterDetails = executable.emrClusterDetails

          val emrJobStateEither = for {
            clusterName <- clusterDetails.clusterName.toRight(
              "Cluster name missing")
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
              clusterDetails.applications.map(new Application().withName(_))

            val args = emrStateHelper.buildSparkArgs(executable)

            // Master, Core and Task instance groups
            val instanceDetails = List(
              Some(toInstanceGroupConfig(InstanceRoleType.MASTER,
                                         masterInstanceGroup)),
              clusterDetails.coreInstanceGroup.map(instanceDetails =>
                toInstanceGroupConfig(InstanceRoleType.CORE, instanceDetails)),
              clusterDetails.taskInstanceGroup.map(instanceDetails =>
                toInstanceGroupConfig(InstanceRoleType.TASK, instanceDetails))
            ).flatten

            // Configuration of the EMR instances
            var jobFlowInstancesConfig = new JobFlowInstancesConfig()
              .withKeepJobFlowAliveWhenNoSteps(false)
              .withInstanceGroups(instanceDetails: _*)

            // Add Ec2Subnet if subnet defined in configuration
            jobFlowInstancesConfig = executable.emrClusterDetails.ec2Subnet
              .fold(jobFlowInstancesConfig)(
                jobFlowInstancesConfig.withEc2SubnetId(_))

            val loadDataSteps = emrStateHelper.toStepConfig(executable.loadData)
            val saveResultSteps =
              emrStateHelper.toStepConfig(executable.saveResults)

            val stepConfigs = loadDataSteps ++
              List(emrStateHelper.buildStepConfig(executable.jobName, args)) ++
              saveResultSteps

            // The actual cluster to launch
            val request = new RunJobFlowRequest()
              .withName(clusterName)
              .withReleaseLabel(releaseLabel)
              .withSteps(stepConfigs: _*)
              .withApplications(applications: _*)
              .withLogUri(logUri)
              .withServiceRole(emrServiceRole)
              .withJobFlowRole(emrJobFlowRole)
              .withInstances(jobFlowInstancesConfig)
              .withVisibleToAllUsers(isVisibleToAllUsers)
              .withConfigurations(toConfigurations(
                executable.emrClusterDetails.configurations))
              .withSecurityConfiguration(
                executable.emrClusterDetails.securityConfiguration.orNull)

            // aka the cluster id
            try {
              val flowId = emrClient.runJobFlow(request).getJobFlowId

              val steps = emrClient
                .listSteps(new ListStepsRequest().withClusterId(flowId))
                .getSteps
                .asScala

              val jobState = EmrJobState(
                task.id,
                executable.jobName,
                flowId,
                steps.map(_.getId),
                executable.region,
                new Date(),
                emrStateHelper.getOverallExecutorState(
                  steps.map(_.getStatus.getState))
              )

              Logger.info(s"Submitted $jobState to cluster")
              jobState

            } catch {
              case NonFatal(e) =>
                Logger.error("Exception creating EMR cluster", e)
                EmrJobState(
                  task.id,
                  executable.jobName,
                  "N/A",
                  List("N/A"),
                  executable.region,
                  new Date(),
                  ExecutorStatus.Failed(
                    Some(
                      s"Could not create new EMR cluster due to ${e.getMessage}")
                  )
                )
            }

          }

          // This is sub-optimal (check for-comp on Option), but if for whichever reason the configuration is broken, this won't start any cluster and fail the job
          emrJobStateEither.fold(
            errorMessage => {
              Logger.error(
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
      }
      .unsafeRunSync()

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

    emrClientFactory
      .emrClientResource(Regions.fromName(state.region))
      .use { emrClient =>
        IO {
          val emrJobExecutable = task.executable.asInstanceOf[EmrJobExecutable]

          if (emrJobExecutable.emrClusterDetails.existingCluster) {

            val cancelStepsRequest = new CancelStepsRequest()
              .withClusterId(state.clusterId)
              .withStepIds(state.stepIds: _*)
            val response = emrClient.cancelSteps(cancelStepsRequest)
            val stepCancelResponse =
              response.getCancelStepsInfoList.asScala.head

            Logger.info(
              s"stepCancelResponse - Reason: ${stepCancelResponse.getReason}, Status: ${stepCancelResponse.getStatus}")

          } else {
            val terminateJobFlowsRequest =
              new TerminateJobFlowsRequest().withJobFlowIds(state.clusterId)
            emrClient.terminateJobFlows(terminateJobFlowsRequest)
          }
        }
      }
      .unsafeRunSync()

  }

  override protected def actuallyRefreshState(state: EmrJobState)(
      implicit dao: SundialDao): EmrJobState = {
    emrClientFactory
      .emrClientResource(Regions.fromName(state.region))
      .use { emrClient =>
        IO {

          val listStepsRequest = new ListStepsRequest()
            .withClusterId(state.clusterId)
            .withStepIds(state.stepIds: _*)
          try {
            val statuses = emrClient
              .listSteps(listStepsRequest)
              .getSteps
              .asScala
              .map(_.getStatus.getState)
            state.copy(
              status = emrStateHelper.getOverallExecutorState(statuses))
          } catch {
            case NonFatal(t) => {
              Logger.error(s"Could not refresh State($state)", t)
              state.copy(status = ExecutorStatus.Failed(Some(t.getMessage)))
            }
          }

        }
      }
      .unsafeRunSync()
  }

}
