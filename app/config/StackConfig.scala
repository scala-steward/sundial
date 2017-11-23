package config

import java.util.Arrays.asList
import javax.inject.Named

import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.model.{DescribeStackResourceRequest, DescribeStacksRequest}
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.model.{DescribeTagsRequest, Filter}
import com.amazonaws.util.EC2MetadataUtils
import com.google.inject.{AbstractModule, Provides, Singleton}

class StackConfig extends AbstractModule {

  override def configure() = {

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

}
