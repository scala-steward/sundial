package service.emr

import model.{CopyFileJob, EmrExecutorState, EmrJobExecutable, ExecutorStatus}
import EmrStepHelper._
import software.amazon.awssdk.services.emr.model.{
  ActionOnFailure,
  HadoopJarStepConfig,
  StepConfig,
  StepState
}

class EmrStepHelper {

  def getOverallExecutorState(states: Seq[StepState]): ExecutorStatus = {

    states match {
      case jobStates
          if jobStates.forall(state => state == StepState.COMPLETED) =>
        ExecutorStatus.Succeeded
      case jobStates if jobStates.contains(StepState.FAILED) =>
        ExecutorStatus.Failed(None)
      case jobStates if jobStates.contains(StepState.CANCELLED) =>
        EmrExecutorState.Cancelled
      case jobStates if jobStates.contains(StepState.INTERRUPTED) =>
        EmrExecutorState.Interrupted
      case jobStates if jobStates.contains(StepState.CANCEL_PENDING) =>
        EmrExecutorState.CancelPending
      case jobStates if jobStates.contains(StepState.RUNNING) =>
        ExecutorStatus.Running
      case jobStates if jobStates.contains(StepState.PENDING) =>
        ExecutorStatus.Initializing
      case jobStates =>
        throw new IllegalArgumentException(
          s"Unexpected States combination(${jobStates.mkString(",")})")
    }

  }

  def toStepConfig(cpFilesStepsOpt: Option[Seq[CopyFileJob]],
                   isStaticCluster: Boolean = false): Seq[StepConfig] = {
    cpFilesStepsOpt match {
      case Some(copyFileJobs) if copyFileJobs.nonEmpty =>
        copyFileJobs.map(
          cpFileJob =>
            createS3DistCpJob(cpFileJob.source,
                              cpFileJob.destination,
                              isStaticCluster))
      case _ => Vector.empty[StepConfig]
    }
  }

  /**
    * Builds the spark submit ARGS=[]
    *
    * @param executable
    * @return
    */
  def buildSparkArgs(executable: EmrJobExecutable) = {
    val sparkConfigs = executable.sparkConf
      .flatMap(conf => List(ConfOption, conf))

    val packages = if (executable.sparkPackages.isEmpty) {
      List.empty
    } else {
      List(
        PackagesOption,
        executable.sparkPackages
          .map { mavenPackage =>
            mavenPackage.groupId + ":" + mavenPackage.artifactId + ":" + mavenPackage.version
          }
          .mkString(","))
    }

    List(SparkSubmitCommand) ++
      sparkConfigs ++
      packages ++
      List(
        ClassOption,
        executable.clazz,
        executable.s3JarPath
      ) ++
      executable.args
  }

  def buildStepConfig(jobName: String,
                      args: Seq[String],
                      actionOnFailure: ActionOnFailure =
                        ActionOnFailure.TERMINATE_CLUSTER) = {
    StepConfig
      .builder()
      .name(jobName)
      .actionOnFailure(actionOnFailure)
      .hadoopJarStep(
        HadoopJarStepConfig
          .builder()
          .jar(CommandRunnerJar)
          .args(args: _*)
          .build()
      )
      .build()
  }

  private val S3DistCp = "s3-dist-cp"

  private def createS3DistCpJob(source: String,
                                destination: String,
                                isStaticCluster: Boolean): StepConfig = {
    val actionOnFailure = if (isStaticCluster) {
      ActionOnFailure.CONTINUE
    } else {
      ActionOnFailure.TERMINATE_CLUSTER
    }
    StepConfig
      .builder()
      .actionOnFailure(actionOnFailure)
      .name(S3DistCp)
      .hadoopJarStep(
        HadoopJarStepConfig
          .builder()
          .jar(CommandRunnerJar)
          .args(
            List(
              S3DistCp,
              "--s3Endpoint=s3.amazonaws.com",
              s"--src=$source",
              s"--dest=$destination"
            ): _*
          )
          .build()
      )
      .build()
  }

}

object EmrStepHelper {

  private val SparkSubmitCommand = "spark-submit"

  private val ConfOption = "--conf"

  private val ClassOption = "--class"

  private val PackagesOption = "--packages"

  private val CommandRunnerJar = "command-runner.jar"

  def apply(): EmrStepHelper = new EmrStepHelper()

}
