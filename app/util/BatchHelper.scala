package util

import java.util.UUID

import javax.inject.{Inject, Singleton}
import play.api.Logging
import software.amazon.awssdk.services.batch.BatchClient
import software.amazon.awssdk.services.batch.model._

import scala.collection.JavaConverters._

// The AWS SDK doesn't provide proper model classes, so we make our own

case class BatchJobDefinition(definitionName: String,
                              container: BatchContainerDefinition,
                              jobRoleArn: Option[String])
case class BatchContainerDefinition(image: String,
                                    vCpus: Int,
                                    memory: Int,
                                    command: Seq[String],
                                    environmentVariables: Map[String, String] =
                                      Map.empty,
                                    computeEnvironment: Option[String] = None)

@Singleton
class BatchHelper @Inject()() extends Logging {

  /**
    *
    * @param jobName cerebro-hello_sundial
    * @param jobQueue sundial
    * @param startedBy An optional tag specified when a task is started. For example if you automatically trigger a task to run a batch process job, you could apply a unique identifier for that job to your task with the startedBy parameter. You can then identify which tasks belong to that job by filtering the results of a ListTasks call with the startedBy value.
    * @param batchClient
    * @return
    */
  def runJob(jobName: String,
             jobQueue: String,
             jobDefinitionName: String,
             startedBy: String)(
      implicit batchClient: BatchClient): SubmitJobResponse = {

    val runTaskRequest = SubmitJobRequest
      .builder()
      .jobQueue(jobQueue)
      .jobName(jobName)
      .jobDefinition(jobDefinitionName)
      .build()

    batchClient.submitJob(runTaskRequest)
  }

  def registerJobDefinition(jobDefinition: BatchJobDefinition)(
      implicit batchClient: BatchClient): RegisterJobDefinitionResponse = {

    val containerDef = jobDefinition.container
    val containerPropertiesBuilder = ContainerProperties
      .builder()
      .command(containerDef.command.asJavaCollection)
      .environment(
        containerDef.environmentVariables
          .map {
            case (key, value) =>
              KeyValuePair.builder().name(key).value(value).build()
          }
          .toList
          .asJava)
      .image(containerDef.image)
      .memory(containerDef.memory)
      .vcpus(containerDef.vCpus)

    val containerPropertiesWithJobRoleArn = jobDefinition.jobRoleArn
      .fold(containerPropertiesBuilder)(
        containerPropertiesBuilder.jobRoleArn(_))
      .build()

    val registerTaskDefinitionRequest = RegisterJobDefinitionRequest
      .builder()
      .containerProperties(containerPropertiesWithJobRoleArn)
      .jobDefinitionName(jobDefinition.definitionName)
      .`type`(JobDefinitionType.CONTAINER)
      .build()

    // Note: If you register the same TaskDefinition Name multiple times ECS with create a new 'revision'
    batchClient.registerJobDefinition(registerTaskDefinitionRequest)
  }

  /**
    *
    * @param jobId job id
    * @param reason "Why we are stopping this task"
    * @param batchClient
    * @return
    */
  def stopTask(jobId: String, reason: String)(
      implicit batchClient: BatchClient): TerminateJobResponse = {
    val cancelJobRequest = CancelJobRequest
      .builder()
      .jobId(jobId)
      .reason(reason)
      .build()
    batchClient.cancelJob(cancelJobRequest)

    val terminateJobRequest = TerminateJobRequest
      .builder()
      .jobId(jobId)
      .reason(reason)
      .build()

    batchClient.terminateJob(terminateJobRequest)
  }

  def describeJobDefinition(jobDefinitionName: String)(
      implicit batchClient: BatchClient): Option[JobDefinition] = {
    val request = DescribeJobDefinitionsRequest
      .builder()
      .jobDefinitionName(jobDefinitionName)
      .build()
    batchClient
      .describeJobDefinitions(request)
      .jobDefinitions()
      .asScala
      .sortBy(_.revision().intValue() * -1)
      .headOption
  }

  private def asInternalModel(
      jobDefinition: JobDefinition): BatchJobDefinition = {
    val containerProperties = jobDefinition.containerProperties()
    val containerDefintion = BatchContainerDefinition(
      image = containerProperties.image(),
      vCpus = containerProperties.vcpus(),
      memory = containerProperties.memory(),
      command = containerProperties.command().asScala.toList,
      environmentVariables = containerProperties
        .environment()
        .asScala
        .map { kvp =>
          kvp.name() -> kvp.value()
        }
        .toMap
    )
    BatchJobDefinition(definitionName = jobDefinition.jobDefinitionName(),
                       container = containerDefintion,
                       jobRoleArn = Option(containerProperties.jobRoleArn()))
  }

  def matches(actual: JobDefinition, expected: BatchJobDefinition): Boolean = {
    val actualInternal = asInternalModel(actual)
    if (actualInternal != expected) {
      logger.info(s"desired Batch job definition: $expected")
      logger.info(s"actual Batch job definition: $actualInternal")
      false
    } else {
      true
    }
  }

  def describeJob(jobId: UUID)(
      implicit batchClient: BatchClient): Option[JobDetail] = {

    val describeJobsRequest = DescribeJobsRequest
      .builder()
      .jobs(jobId.toString)
      .build()
    val describeJobsResponse = batchClient
      .describeJobs(describeJobsRequest)

    describeJobsResponse.jobs().asScala.headOption
  }

}
