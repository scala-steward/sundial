package util

import com.amazonaws.services.ecs.AmazonECSClient
import com.amazonaws.services.ecs.model._
import play.api.Logger

import scala.collection.JavaConverters._

// The AWS SDK doesn't provide proper model classes, so we make our own

case class ECSTaskDefinition(containers: Seq[ECSContainerDefinition], volumes: Seq[ECSVolume], family: String, taskRoleArn: Option[String])
case class ECSContainerLink(sourceContainer: String, internalName: String)
case class ECSContainerDefinition(name: String, image: String, cpu: Int, memory: Int,
                                  command: Seq[String], essential: Boolean,
                                  environmentVariables: Map[String, String] = Map.empty,
                                  mountPoints: Seq[ECSMountPoint] = Seq.empty, links: Seq[ECSContainerLink] = Seq.empty)
case class ECSMountPoint(containerPath: String, sourceVolume: String)
case class ECSVolume(name: String, hostSourcePath: Option[String])
case class ECSContainerOverride(name: String, command: Seq[String])

object ECSHelper {

  // startedBy
  //


  /**
   *
   * @param taskDefinition cerebro-hello_sundial
   * @param cluster sundial
   * @param startedBy An optional tag specified when a task is started. For example if you automatically trigger a task to run a batch process job, you could apply a unique identifier for that job to your task with the startedBy parameter. You can then identify which tasks belong to that job by filtering the results of a ListTasks call with the startedBy value.
   * @param ecsClient
   * @return
   */
  def runTask(taskDefinition: String, cluster: String, startedBy: String, overrides: Seq[ECSContainerOverride] = Seq.empty)(implicit ecsClient: AmazonECSClient): RunTaskResult ={

    val runTaskRequest = new RunTaskRequest()
      .withCluster(cluster)
      .withCount(1)
      .withStartedBy(startedBy)
      .withTaskDefinition(taskDefinition)
      .withOverrides(new TaskOverride().withContainerOverrides(
        overrides.map(o => new ContainerOverride().withName(o.name).withCommand(o.command.asJavaCollection)).asJavaCollection)
      )
    // TODO: would this stuff be useful?
//      .withGeneralProgressListener(null)
//      .withRequestMetricCollector(null)

    ecsClient.runTask(runTaskRequest)
  }


  def registerTaskDefinition(taskDefinition: ECSTaskDefinition)
                            (implicit ecsClient: AmazonECSClient): RegisterTaskDefinitionResult ={
    val containerDefinitions = taskDefinition.containers.map { definition =>
      new ContainerDefinition()
        .withCpu(definition.cpu)
        .withMemory(definition.memory)
        .withName(definition.name)
        .withImage(definition.image)
        .withCommand(definition.command.asJava)
        .withEssential(definition.essential)
        .withEnvironment(definition.environmentVariables.map { case (key, value) =>
          new KeyValuePair().withName(key).withValue(value)
        }.toList.asJava)
        .withLinks(definition.links.map { link =>
          s"${link.sourceContainer}:${link.internalName}"
        }.asJava)
        .withMountPoints(definition.mountPoints.map { mountPoint =>
          new MountPoint().withContainerPath(mountPoint.containerPath).withSourceVolume(mountPoint.sourceVolume)
        }.asJava)
    }
    val volumes = taskDefinition.volumes.map { volume =>
      new Volume()
        .withName(volume.name)
        .withHost(new HostVolumeProperties().withSourcePath(volume.hostSourcePath.orNull))
    }
    val registerTaskDefinitionRequest = new RegisterTaskDefinitionRequest()
      .withFamily(taskDefinition.family)
      .withContainerDefinitions(containerDefinitions.asJava)
      .withVolumes(volumes.asJava)

    val registerTaskDefinitionWithTaskRoleArn = taskDefinition.taskRoleArn.fold(registerTaskDefinitionRequest)(registerTaskDefinitionRequest.withTaskRoleArn(_))

    // Note: If you register the same TaskDefinition Name multiple times ECS with create a new 'revision'
    ecsClient.registerTaskDefinition(registerTaskDefinitionWithTaskRoleArn)
  }


  /**
   *
   * @param cluster sundial
   * @param taskArn arn:aws:ecs:us-east-1:470841446682:task-definition/cerebro-hello_sundial
   * @param ecsClient
   * @return
   */
  def stopTask(cluster: String, taskArn: String)(implicit ecsClient: AmazonECSClient): StopTaskResult ={
    val stopTaskRequest = new StopTaskRequest()
      .withCluster(cluster)
      .withTask(taskArn)
    ecsClient.stopTask(stopTaskRequest)
  }


  def listTaskDefinitionFamilies(family: String = "")(implicit ecsClient: AmazonECSClient): Set[String] = {
    val request = new ListTaskDefinitionFamiliesRequest().withFamilyPrefix(family)
    ecsClient.listTaskDefinitionFamilies(request).getFamilies.asScala.toSet
  }

  def describeTaskDefinition(family: String, revision: Option[String])(implicit ecsClient: AmazonECSClient): TaskDefinition = {
    val query = revision.map(r => s"$family:$r").getOrElse(family)
    val request = new DescribeTaskDefinitionRequest().withTaskDefinition(query)
    ecsClient.describeTaskDefinition(request).getTaskDefinition
  }

  private def asInternalModel(taskDefinition: TaskDefinition): ECSTaskDefinition = {
    ECSTaskDefinition(family = taskDefinition.getFamily,
                      containers = taskDefinition.getContainerDefinitions.asScala.map { containerDef =>
                        ECSContainerDefinition(name = containerDef.getName,
                                               image = containerDef.getImage,
                                               cpu = containerDef.getCpu,
                                               memory = containerDef.getMemory,
                                               command = containerDef.getCommand.asScala.toList,
                                               essential = containerDef.getEssential,
                                               environmentVariables = containerDef.getEnvironment.asScala.map { kvp =>
                                                 kvp.getName -> kvp.getValue
                                               }.toMap,
                                               links = containerDef.getLinks.asScala.map { link =>
                                                 val parts = link.split(":")
                                                 ECSContainerLink(parts(0), parts(1))
                                               },
                                               mountPoints = containerDef.getMountPoints.asScala.map { mountPoint =>
                                                 ECSMountPoint(containerPath = mountPoint.getContainerPath,
                                                               sourceVolume = mountPoint.getSourceVolume)
                                               }.toList)
                      }.toList,
                      volumes = taskDefinition.getVolumes.asScala.map { volume =>
                        ECSVolume(name = volume.getName,
                                  hostSourcePath = Option(volume.getHost).flatMap(h => Option(h.getSourcePath)))
                      }.toList,
                      taskRoleArn = Option(taskDefinition.getTaskRoleArn))
  }

  def matches(actual: TaskDefinition, expected: ECSTaskDefinition): Boolean = {
    val actualInternal = asInternalModel(actual)
    if(actualInternal != expected) {
      Logger.info(s"desired ECS task definition: $expected")
      Logger.info(s"actual ECS task definition: $actualInternal")
      false
    } else {
      true
    }
  }

  def describeTask(cluster: String, arn: String)(implicit ecsClient: AmazonECSClient): Option[Task] ={
    val describeTasksRequest = new DescribeTasksRequest()
      .withCluster(cluster)
      .withTasks(arn)
    val describeTasksResponse = ecsClient.describeTasks(describeTasksRequest)
    describeTasksResponse.getTasks.asScala.headOption
  }

}
