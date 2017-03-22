package common

import java.util.Arrays.asList
import java.util.concurrent.TimeUnit

import com.amazonaws.regions.Regions
import com.amazonaws.services.cloudformation.AmazonCloudFormationClient
import com.amazonaws.services.cloudformation.model.{DescribeStackResourceRequest, DescribeStacksRequest}
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.{DescribeTagsRequest, Filter}
import com.amazonaws.services.ecs.model.{PlacementStrategy, PlacementStrategyType}
import com.amazonaws.util.EC2MetadataUtils
import play.api.{Application, GlobalSettings, Logger}
import service.{Dependencies, Sundial}

import scala.collection.JavaConverters._

object SundialGlobal extends GlobalSettings {

  lazy val dependencies: Dependencies = new ConfigDependencies()

  lazy val sundial: Sundial = Bootstrap.bootstrapSundial(dependencies)

  lazy val awsRegion = play.Play.application.configuration.getString("aws.region")
  lazy val cfnClient: AmazonCloudFormationClient = new AmazonCloudFormationClient().withRegion(Regions.valueOf(awsRegion))
  lazy val ec2Client: AmazonEC2Client = new AmazonEC2Client().withRegion(Regions.valueOf(awsRegion))

  lazy val cfnStackName = {
    val instanceId = EC2MetadataUtils.getInstanceId
    val filter1 = new Filter("resource-type", asList("instance"))
    val filter2 = new Filter("resource-id", asList(instanceId))
    val filter3 = new Filter("key", asList("aws:cloudformation:stack-id"))
    val describeTagsRequest = new DescribeTagsRequest().withFilters(filter1, filter2, filter3)
    val tags = ec2Client.describeTags(describeTagsRequest).getTags
    tags.asScala.find(_.getKey == "aws:cloudformation:stack-id").get.getValue
  }

  lazy val s3Bucket = {
    val describeStackRequest = new DescribeStackResourceRequest().withStackName(cfnStackName).withLogicalResourceId("S3Bucket")
    cfnClient.describeStackResource(describeStackRequest).getStackResourceDetail.getPhysicalResourceId
  }

  lazy val sdbDomain = {
    val describeStackRequest = new DescribeStackResourceRequest().withStackName(cfnStackName).withLogicalResourceId("SimpleDBDomain")
    cfnClient.describeStackResource(describeStackRequest).getStackResourceDetail.getPhysicalResourceId
  }

  lazy val sundialUrl = {
    val describeStackRequest = new DescribeStacksRequest().withStackName(cfnStackName)
    val stack = cfnClient.describeStacks(describeStackRequest).getStacks.get(0)
    stack.getOutputs.asScala.find(_.getOutputKey == "WebAddress").get.getOutputValue
  }

  lazy val taskPlacementStrategy = {
    val conf = play.Play.application.configuration
    val taskPlacementString = conf.getString("ecs.defaultTaskPlacement", "random").toLowerCase
    val binpackPlacement = conf.getString("ecs.binpackPlacement", "memory").toLowerCase
    val spreadPlacement = conf.getString("ecs.spreadPlacement", "host").toLowerCase
    val placementStrategyType = PlacementStrategyType.fromValue(taskPlacementString)
    val placementStrategy = new PlacementStrategy().withType(placementStrategyType)
    placementStrategyType match {
      case PlacementStrategyType.Binpack => placementStrategy.withField(binpackPlacement)
      case PlacementStrategyType.Random => placementStrategy
      case PlacementStrategyType.Spread => placementStrategy.withField(spreadPlacement)
    }
  }

  override def onStart(application: Application): Unit = {
    Logger.info("Starting svc-sundial with config:")
    configuration.entrySet.foreach { entry =>
      Logger.info(s"\t${entry._1} = [${entry._2.toString}]")
    }

    //TODO should be config
    sundial.start(10, TimeUnit.SECONDS)
  }

  override def onStop(application: Application): Unit = {
    sundial.stop()
  }

}
