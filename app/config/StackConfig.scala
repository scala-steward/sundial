package config

import javax.inject.Named
import com.google.inject.{AbstractModule, Provides, Singleton}
import software.amazon.awssdk.regions.internal.util.EC2MetadataUtils
import software.amazon.awssdk.services.cloudformation.CloudFormationClient
import software.amazon.awssdk.services.cloudformation.model.{
  DescribeStackResourceRequest,
  DescribeStacksRequest
}
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.DescribeTagsRequest
import software.amazon.awssdk.services.ec2.model.Filter

class StackConfig extends AbstractModule {

  override def configure() = {}

  @Provides
  @Named("cfnStackName")
  @Singleton
  def cfnStackName(ec2Client: Ec2Client): String = {
    import scala.collection.JavaConverters._
    val instanceId = EC2MetadataUtils.getInstanceId
    val resourceTypeFilter =
      Filter.builder.name("resource-type").values("instance").build()
    val resourceIdFilter =
      Filter.builder().name("resource-id").values(instanceId).build()
    val stackIdFilter =
      Filter.builder().name("key").values("aws:cloudformation:stack-id").build()
    val describeTagsRequest = DescribeTagsRequest
      .builder()
      .filters(resourceTypeFilter, resourceIdFilter, stackIdFilter)
      .build()
    val tags = ec2Client.describeTags(describeTagsRequest).tags().asScala
    tags
      .find(_.key() == "aws:cloudformation:stack-id")
      .getOrElse(sys.error("aws:cloudformation:stack-id tag is compulsory"))
      .value()
  }

  @Provides
  @Named("s3Bucket")
  @Singleton
  def s3Bucket(cfnClient: CloudFormationClient,
               @Named("cfnStackName") cfnStackName: String): String = {
    val describeStackRequest = DescribeStackResourceRequest
      .builder()
      .stackName(cfnStackName)
      .logicalResourceId("S3Bucket")
      .build()
    cfnClient
      .describeStackResource(describeStackRequest)
      .stackResourceDetail()
      .physicalResourceId()
  }
  @Provides
  @Named("sundialUrl")
  @Singleton
  def sundialUrl(cfnClient: CloudFormationClient,
                 @Named("cfnStackName") cfnStackName: String) = {
    import scala.collection.JavaConverters._
    val describeStackRequest =
      DescribeStacksRequest.builder().stackName(cfnStackName).build()
    val stack =
      cfnClient.describeStacks(describeStackRequest).stacks().get(0)
    stack
      .outputs()
      .asScala
      .find(_.outputKey() == "WebAddress")
      .get
      .outputValue()
  }

}
