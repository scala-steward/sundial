package util

import java.util.UUID
import javax.inject.{Inject, Singleton}

import com.amazonaws.services.batch.AWSBatch
import com.amazonaws.services.batch.model._
import play.api.Logger

import scala.collection.JavaConverters._

// The AWS SDK doesn't provide proper model classes, so we make our own

case class BatchJobDefinition(definitionName: String, container: BatchContainerDefinition, jobRoleArn: Option[String])
case class BatchContainerDefinition(image: String, vCpus: Int, memory: Int,
                                    command: Seq[String],
                                    environmentVariables: Map[String, String] = Map.empty,
                                    computeEnvironment: Option[String] = None)

@Singleton
class BatchHelper @Inject()() {


  /**
    *
    * @param jobName cerebro-hello_sundial
    * @param jobQueue sundial
    * @param startedBy An optional tag specified when a task is started. For example if you automatically trigger a task to run a batch process job, you could apply a unique identifier for that job to your task with the startedBy parameter. You can then identify which tasks belong to that job by filtering the results of a ListTasks call with the startedBy value.
    * @param batchClient
    * @return
    */
  def runJob(jobName: String, jobQueue: String, jobDefinitionName: String, startedBy: String)(implicit batchClient: AWSBatch): SubmitJobResult ={
    val runTaskRequest = new SubmitJobRequest()
      .withJobQueue(jobQueue)
      .withJobName(jobName)
      .withJobDefinition(jobDefinitionName)
      .withDependsOn()

    batchClient.submitJob(runTaskRequest)
  }


  def registerJobDefinition(jobDefinition: BatchJobDefinition)
                           (implicit batchClient: AWSBatch): RegisterJobDefinitionResult ={
    val containerDef = jobDefinition.container
    val containerProperties = new ContainerProperties()
      .withCommand(containerDef.command.asJavaCollection)
      .withEnvironment(containerDef.environmentVariables.map { case (key, value) =>
        new KeyValuePair().withName(key).withValue(value)
      }.toList.asJava)
      .withImage(containerDef.image)
      .withMemory(containerDef.memory)
      .withVcpus(containerDef.vCpus)

    val containerPropertiesWithJobRoleArn = jobDefinition.jobRoleArn.fold(containerProperties)(containerProperties.withJobRoleArn(_))

    val registerTaskDefinitionRequest = new RegisterJobDefinitionRequest()
      .withContainerProperties(containerPropertiesWithJobRoleArn)
      .withJobDefinitionName(jobDefinition.definitionName)
      .withType(JobDefinitionType.Container)

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
  def stopTask(jobId:String, reason: String)(implicit batchClient: AWSBatch): TerminateJobResult ={
    val cancelJobRequest = new CancelJobRequest()
      .withJobId(jobId)
      .withReason(reason)
    batchClient.cancelJob(cancelJobRequest)

    val terminateJobRequest = new TerminateJobRequest()
      .withJobId(jobId)
      .withReason(reason)

    batchClient.terminateJob(terminateJobRequest)
  }

  def describeJobDefinition(jobDefinitionName: String)(implicit batchClient: AWSBatch): Option[JobDefinition] = {
    val request = new DescribeJobDefinitionsRequest().withJobDefinitionName(jobDefinitionName)
    batchClient.describeJobDefinitions(request).getJobDefinitions.asScala.sortBy(_.getRevision.intValue() * -1).headOption
  }

  private def asInternalModel(jobDefinition: JobDefinition): BatchJobDefinition = {
    val containerProperties = jobDefinition.getContainerProperties
    val containerDefintion = BatchContainerDefinition(
      image = containerProperties.getImage,
      vCpus = containerProperties.getVcpus,
      memory = containerProperties.getMemory,
      command = containerProperties.getCommand.asScala.toList,
      environmentVariables = containerProperties.getEnvironment.asScala.map { kvp =>
        kvp.getName -> kvp.getValue
      }.toMap
    )
    BatchJobDefinition(definitionName = jobDefinition.getJobDefinitionName,
      container = containerDefintion,
      jobRoleArn = Option(containerProperties.getJobRoleArn))
  }

  def matches(actual: JobDefinition, expected: BatchJobDefinition): Boolean = {
    val actualInternal = asInternalModel(actual)
    if(actualInternal != expected) {
      Logger.info(s"desired Batch job definition: $expected")
      Logger.info(s"actual Batch job definition: $actualInternal")
      false
    } else {
      true
    }
  }

  def describeJob(jobId: UUID)(implicit batchClient: AWSBatch): Option[JobDetail] ={
    val describeJobsRequest = new DescribeJobsRequest()
      .withJobs(jobId.toString)
    val describeJobsResponse = batchClient.describeJobs(describeJobsRequest)
    describeJobsResponse.getJobs.asScala.headOption
  }

}