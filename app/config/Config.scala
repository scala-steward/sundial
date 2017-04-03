package config

import java.util.Arrays.asList
import javax.inject.{Inject, Named}

import com.amazonaws.services.cloudformation.{AmazonCloudFormation, AmazonCloudFormationClientBuilder}
import com.amazonaws.services.cloudformation.model.{DescribeStackResourceRequest, DescribeStacksRequest}
import com.amazonaws.services.ec2.{AmazonEC2, AmazonEC2ClientBuilder}
import com.amazonaws.services.ec2.model.{DescribeTagsRequest, Filter}
import com.amazonaws.services.ecs.model.{PlacementStrategy, PlacementStrategyType}
import com.amazonaws.services.ecs.{AmazonECS, AmazonECSClientBuilder}
import com.amazonaws.services.logs.{AWSLogs, AWSLogsClientBuilder}
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.amazonaws.services.simpledb.{AmazonSimpleDB, AmazonSimpleDBClientBuilder}
import com.amazonaws.services.simpleemail.{AmazonSimpleEmailServiceAsync, AmazonSimpleEmailServiceAsyncClientBuilder}
import com.amazonaws.util.EC2MetadataUtils
import com.google.inject.{AbstractModule, Provides, Singleton}
import dao.SundialDaoFactory
import dto.DisplayModels
import org.lyranthe.prometheus.client.{DefaultRegistry, Registry, jmx}
import play.api.libs.ws.WSClient
import play.api.{Configuration, Environment, Logger}
import service._
import service.notifications.{DevelopmentEmailNotifications, EmailNotifications, Notification, PagerdutyNotifications}

@Singleton
class PrometheusJmxInstrumentation @Inject()(implicit val registry: Registry) {
  jmx.register
}

class Config(environment: Environment, configuration: Configuration) extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[PrometheusJmxInstrumentation]).asEagerSingleton()
    bind(classOf[Sundial]).asEagerSingleton()

    Logger.info("Starting svc-sundial with config:")
    configuration.entrySet.foreach { entry =>
      Logger.info(s"\t${entry._1} = [${entry._2.toString}]")
    }

  }

  @Provides
  @Singleton
  def s3Client(): AmazonS3 = {
    AmazonS3ClientBuilder.defaultClient()
  }

  @Provides
  @Singleton
  def prometheusRegistry: Registry = {
    DefaultRegistry()
  }

  @Provides
  @Singleton
  def awsLogsClient: AWSLogs = {
    AWSLogsClientBuilder.defaultClient
  }

  @Provides
  @Singleton
  def ecsClient(): AmazonECS = {
    AmazonECSClientBuilder.defaultClient()
  }

  @Provides
  @Singleton
  def ec2Client: AmazonEC2 = {
    AmazonEC2ClientBuilder.defaultClient()
  }

  @Provides
  @Singleton
  def cfnClient(): AmazonCloudFormation = {
    AmazonCloudFormationClientBuilder.defaultClient()
  }

  @Provides
  @Named("cfnStackName")
  @Singleton
  def cfnStackName(ec2Client: AmazonEC2): String = {
    import scala.collection.JavaConverters._
    val instanceId = EC2MetadataUtils.getInstanceId
    val resourceTypeFilter = new Filter("resource-type", asList("instance"))
    val resourceIdFilter = new Filter("resource-id", asList(instanceId))
    val stackIdFilter = new Filter("key", asList("aws:cloudformation:stack-id"))
    val describeTagsRequest = new DescribeTagsRequest().withFilters(resourceTypeFilter, resourceIdFilter, stackIdFilter)
    val tags = ec2Client.describeTags(describeTagsRequest).getTags
    tags.asScala.find(_.getKey == "aws:cloudformation:stack-id").getOrElse(sys.error("aws:cloudformation:stack-id tag is compulsory")).getValue
  }

  @Provides
  @Named("s3Bucket")
  @Singleton
  def s3Bucket(cfnClient: AmazonCloudFormation, @Named("cfnStackName") cfnStackName: String): String = {
    val describeStackRequest = new DescribeStackResourceRequest().withStackName(cfnStackName).withLogicalResourceId("S3Bucket")
    cfnClient.describeStackResource(describeStackRequest).getStackResourceDetail.getPhysicalResourceId
  }

  @Provides
  @Named("sdbDomain")
  @Singleton
  def sdbDomain(cfnClient: AmazonCloudFormation, @Named("cfnStackName") cfnStackName: String): String = {
    val describeStackRequest = new DescribeStackResourceRequest().withStackName(cfnStackName).withLogicalResourceId("SimpleDBDomain")
    cfnClient.describeStackResource(describeStackRequest).getStackResourceDetail.getPhysicalResourceId
  }

  @Provides
  @Named("sundialUrl")
  @Singleton
  def sundialUrl(cfnClient: AmazonCloudFormation, @Named("cfnStackName") cfnStackName: String) = {
    import scala.collection.JavaConverters._
    val describeStackRequest = new DescribeStacksRequest().withStackName(cfnStackName)
    val stack = cfnClient.describeStacks(describeStackRequest).getStacks.get(0)
    stack.getOutputs.asScala.find(_.getOutputKey == "WebAddress").get.getOutputValue
  }

  @Provides
  @Singleton
  def simpleDbClient(): AmazonSimpleDB = {
    AmazonSimpleDBClientBuilder.defaultClient()
  }

  @Provides
  @Singleton
  def sesClient(): AmazonSimpleEmailServiceAsync = {
    AmazonSimpleEmailServiceAsyncClientBuilder.defaultClient()
  }

  @Provides
  @Singleton
  def notifications(wsClient: WSClient, daoFactory: SundialDaoFactory, displayModels: DisplayModels, sesClient: AmazonSimpleEmailServiceAsync): Seq[Notification] = {
    configuration.getString("notifications.mode") match {
      case Some("browser") => Seq(new DevelopmentEmailNotifications(daoFactory, displayModels, sesClient))
      case Some("email") => Seq(new EmailNotifications(daoFactory, configuration.getString("notifications.from").get, displayModels, sesClient))
      case Some("all") => Seq(
        new EmailNotifications(daoFactory, configuration.getString("notifications.from").get, displayModels, sesClient),
        new PagerdutyNotifications(wsClient, daoFactory)
      )
      case _ => Seq.empty
    }
  }

  @Provides
  @Singleton
  def taskPlacementStrategy(): PlacementStrategy = {
    val taskPlacementString = configuration.getString("ecs.defaultTaskPlacement").getOrElse("random").toLowerCase
    val binpackPlacement = configuration.getString("ecs.binpackPlacement").getOrElse("memory").toLowerCase
    val spreadPlacement = configuration.getString("ecs.spreadPlacement").getOrElse("host").toLowerCase
    val placementStrategyType = PlacementStrategyType.fromValue(taskPlacementString)
    val placementStrategy = new PlacementStrategy().withType(placementStrategyType)
    placementStrategyType match {
      case PlacementStrategyType.Binpack => placementStrategy.withField(binpackPlacement)
      case PlacementStrategyType.Random => placementStrategy
      case PlacementStrategyType.Spread => placementStrategy.withField(spreadPlacement)
    }
  }


}