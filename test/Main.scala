import com.gilt.svc.sundial.v0.models._
import play.api.libs.json.Json

object Main {

  private val CommandRunnerJar = "command-runner.jar"

  def main(args: Array[String]): Unit = {

    import com.gilt.svc.sundial.v0.models.json._

    val taskDefinition = TaskDefinition(
      taskDefinitionName = "bla",
      dependencies = Seq.empty,
      executable = EmrCommand(
        emrCluster = NewEmrCluster(
          name = "Temp Cluster",
          releaseLabel = com.gilt.svc.sundial.v0.models.EmrReleaseLabel.Emr5100,
          applications = Seq(EmrApplication.Spark),
          s3LogUri = "s3://cerebro-spark-cluster-logs",
          masterInstance= EmrInstanceGroupDetails("m4.large", 1, OnDemand.OnDemand),
          coreInstance= Some(EmrInstanceGroupDetails("m4.large", 1, Spot(BigDecimal(0.035)))),
          taskInstance= None,
          ec2Subnet = Some("subnet-4d6bb83a"),
          emrServiceRole = DefaultEmrServiceRole.DefaultEmrServiceRole,
          emrJobFlowRole = DefaultEmrJobFlowRole.DefaultEmrJobFlowRole
        ),
        jobName = "BrandFitGuidanceJob",
        `class` = "com.gilt.cerebro.job.spark.core.JobRunner",
        s3JarPath = "s3://cerebro-spark-jobs-releases/job-cerebro-spark-0.1.1-SNAPSHOT.jar",
        sparkConf = List("spark.driver.extraJavaOptions=-Denvironment=integration"),
        args = List("com.gilt.cerebro.job.spark.elt.BrandFitGuidanceJob")
      ),
      maxAttempts = 1,
      backoffBaseSeconds = 0,
      requireExplicitSuccess = false
    )

    println(Json.prettyPrint(Json.toJson(taskDefinition)))


    //    val emrClient = AmazonElasticMapReduceClientBuilder
    //      .standard()
    //      .withRegion(Regions.US_EAST_1)
    //      .build()
    //
    //    val args = List(
    //      "spark-submit",
    //      "--conf", "spark.driver.extraJavaOptions=-Denvironment=integration",
    //      "--class", "com.gilt.cerebro.job.spark.core.JobRunner",
    //      "s3://cerebro-spark-jobs-releases/job-cerebro-spark-0.1.1-SNAPSHOT.jar"
    //    ) ++ Seq("com.gilt.cerebro.job.spark.elt.BrandFitGuidanceJob")
    //
    //    val request = new RunJobFlowRequest()
    //      .withName("temp-cluster")
    //      .withReleaseLabel("emr-5.10.0")
    //      .withSteps(new StepConfig()
    //        .withName("BrandFitGuidanceJob")
    //        .withActionOnFailure(ActionOnFailure.TERMINATE_CLUSTER)
    //        .withHadoopJarStep(
    //          new HadoopJarStepConfig(CommandRunnerJar)
    //            .withArgs(args: _*)
    //        ))
    //      .withApplications(
    //        new Application()
    //          .withName("Spark")
    //      )
    //      .withLogUri("s3://cerebro-spark-cluster-logs")
    //      .withServiceRole("EMR_DefaultRole")
    //      .withJobFlowRole("EMR_EC2_DefaultRole")
    //      .withInstances(
    //        new JobFlowInstancesConfig()
    //          //          .withEc2KeyName("keypair")
    //          .withInstanceCount(1)
    //          //    "subnet-4d6bb83a",
    //          .withEc2SubnetIds("subnet-c92cbbf3")
    //          .withKeepJobFlowAliveWhenNoSteps(true)
    //          .withMasterInstanceType("m3.xlarge")
    //          .withSlaveInstanceType("m1.large")
    //      )
    //
    //    val flowId = emrClient.runJobFlow(request).getJobFlowId
    //
    //    Logger.info(s"FlowId($flowId)")
    //    println(s"FlowId($flowId)")
    //
    //    val step = emrClient
    //      .listSteps(new ListStepsRequest().withClusterId(flowId))
    //      .getSteps
    //      .asScala
    //      .filter(_.getName.equals("BrandFitGuidanceJob"))
    //      .head
    //
    //    println(s"Step($step)")


  }

}
