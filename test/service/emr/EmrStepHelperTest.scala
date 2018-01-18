package service.emr

import com.amazonaws.services.elasticmapreduce.model.{ActionOnFailure, HadoopJarStepConfig, StepConfig}
import model.{CopyFileJob, EmrExecutorState, EmrJobExecutable, ExecutorStatus}
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.mockito.MockitoSugar
import org.mockito.Mockito._

import scala.collection.Seq

class EmrStepHelperTest extends FlatSpec with MockitoSugar {

  private val emrStateHelper = EmrStepHelper()

  "emrStateHelper" should "return failed" in {

    emrStateHelper.getOverallExecutorState(
      List("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED", "CANCEL_PENDING", "RUNNING", "PENDING")
    ) should be(ExecutorStatus.Failed(None))

  }

  it should "return completed when just one job completed successfully" in {

    emrStateHelper.getOverallExecutorState(
      List("COMPLETED", "COMPLETED", "COMPLETED")
    ) should be(ExecutorStatus.Succeeded)

  }

  it should "return completed when multiple jobs complete successfully" in {

    emrStateHelper.getOverallExecutorState(
      List("COMPLETED", "COMPLETED", "COMPLETED")
    ) should be(ExecutorStatus.Succeeded)

  }

  it should "return cancelled if at least a job got cancelled" in {

    emrStateHelper.getOverallExecutorState(
      List("COMPLETED", "CANCELLED", "COMPLETED")
    ) should be(EmrExecutorState.Cancelled)

  }

  it should "return cancel pending" in {

    emrStateHelper.getOverallExecutorState(
      List("COMPLETED", "RUNNING", "CANCEL_PENDING", "PENDING")
    ) should be(EmrExecutorState.CancelPending)

  }

  it should "return running if at least a job is running and no one else terminated in error or a cancellation is pending" in {

    emrStateHelper.getOverallExecutorState(
      List("COMPLETED", "RUNNING", "PENDING")
    ) should be(ExecutorStatus.Running)

  }

  it should "build no Copy jobs" in {
    emrStateHelper.toStepConfig(None) should be(Seq.empty[StepConfig])
  }

  it should "build one s3 dist cp job" in {

    val s3DistCpStep = new StepConfig()
      .withActionOnFailure(ActionOnFailure.TERMINATE_CLUSTER)
      .withName("s3-dist-cp")
      .withHadoopJarStep(
        new HadoopJarStepConfig("command-runner.jar")
          .withArgs(
            List(
              "s3-dist-cp",
              "--s3Endpoint=s3.amazonaws.com",
              "--src=source",
              "--dest=destination"
            ): _*
          )
      )

    emrStateHelper.toStepConfig(Some(List(CopyFileJob("source", "destination")))) should be(Seq(s3DistCpStep))

  }

  it should "create correct spark args" in {

    val executable = mock[EmrJobExecutable]

    when(executable.sparkConf).thenReturn(Seq.empty[String])
    when(executable.clazz).thenReturn("com.gilt.MainClass")
    when(executable.s3JarPath).thenReturn("s3://bucket")
    when(executable.args).thenReturn(List("arg1", "arg2"))

    emrStateHelper.buildSparkArgs(executable) should be (
      List("spark-submit", "--class", "com.gilt.MainClass", "s3://bucket", "arg1", "arg2")
    )

  }

}
