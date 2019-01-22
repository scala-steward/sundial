package service.emr

import java.util.{Date, UUID}

import dao.memory.InMemorySundialDao
import model.ExecutorStatus.Failed
import model._
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.mockito.MockitoSugar
import software.amazon.awssdk.services.emr.EmrClient
import software.amazon.awssdk.services.emr.model.{
  ListStepsRequest,
  ListStepsResponse,
  RunJobFlowRequest,
  RunJobFlowResponse
}

class EmrServiceExecutorTest extends FlatSpec with MockitoSugar with Matchers {

  private def createMockEmrClient(): EmrClient = {

    val mockEmrClient = mock[EmrClient]
    when(mockEmrClient.runJobFlow(any[RunJobFlowRequest]))
      .thenReturn(RunJobFlowResponse.builder().build())
    when(mockEmrClient.listSteps(any[ListStepsRequest]))
      .thenReturn(ListStepsResponse.builder().build())
    mockEmrClient

  }

  "creating cluster with missing cluster name" should "return appropriate error message" in {
    val mockEmrClient = createMockEmrClient
    val emrServiceExecutor = new EmrServiceExecutor(mockEmrClient)
    implicit val sundialDao = new InMemorySundialDao
    val emrClusterDetails =
      EmrClusterDetails(None, None, existingCluster = false)
    val executable = EmrJobExecutable(emrClusterDetails,
                                      "",
                                      "us-east-1",
                                      "",
                                      "",
                                      Seq.empty,
                                      Seq.empty,
                                      Seq.empty,
                                      None,
                                      None,
                                      None)
    val task = Task(UUID.randomUUID(),
                    UUID.randomUUID(),
                    "",
                    "",
                    executable,
                    0,
                    new Date(),
                    TaskStatus.Running())
    val jobState = emrServiceExecutor.actuallyStartExecutable(executable, task)
    jobState.status should be(
      Failed(
        Some(s"Could not create new EMR cluster due to Cluster name missing")
      ))
  }

  "creating cluster with missing release label" should "return appropriate error message" in {
    val mockEmrClient = createMockEmrClient
    val emrServiceExecutor = new EmrServiceExecutor(mockEmrClient)
    implicit val sundialDao = new InMemorySundialDao
    val emrClusterDetails =
      EmrClusterDetails(Some("ClusterName"), None, existingCluster = false)
    val executable = EmrJobExecutable(emrClusterDetails,
                                      "",
                                      "us-east-1",
                                      "",
                                      "",
                                      Seq.empty,
                                      Seq.empty,
                                      Seq.empty,
                                      None,
                                      None,
                                      None)
    val task = Task(UUID.randomUUID(),
                    UUID.randomUUID(),
                    "",
                    "",
                    executable,
                    0,
                    new Date(),
                    TaskStatus.Running())
    val jobState = emrServiceExecutor.actuallyStartExecutable(executable, task)
    jobState.status should be(
      Failed(
        Some(s"Could not create new EMR cluster due to Release label missing")
      ))
  }

  "creating cluster with all the right things" should "return success" in {
    val mockEmrClient = createMockEmrClient
    val emrServiceExecutor = new EmrServiceExecutor(mockEmrClient)
    implicit val sundialDao = new InMemorySundialDao
    val emrClusterDetails =
      EmrClusterDetails(
        Some("ClusterName"),
        None,
        releaseLabel = Some("emr-5.17.0"),
        s3LogUri = Some("blah"),
        emrServiceRole = Some("blah"),
        emrJobFlowRole = Some("blah"),
        masterInstanceGroup =
          Some(InstanceGroupDetails("m4.large", 1, "ondemand", None, None)),
        visibleToAllUsers = Some(true),
        existingCluster = false
      )
    val executable = EmrJobExecutable(emrClusterDetails,
                                      "",
                                      "us-east-1",
                                      "",
                                      "",
                                      Seq.empty,
                                      Seq.empty,
                                      Seq.empty,
                                      None,
                                      None,
                                      None)
    val task = Task(UUID.randomUUID(),
                    UUID.randomUUID(),
                    "",
                    "",
                    executable,
                    0,
                    new Date(),
                    TaskStatus.Running())
    val jobState = emrServiceExecutor.actuallyStartExecutable(executable, task)
    jobState.status should be(ExecutorStatus.Succeeded)

  }

}
