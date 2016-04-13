import java.util.{Date, UUID}

import dao.postgres._
import dao.postgres.common.TestConnectionPool
import model._
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.play.{PlaySpec, OneAppPerSuite}
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class PostgresDaoSpec extends PlaySpec with OneAppPerSuite with BeforeAndAfterEach {

  def makeTestLock() = new PostgresGlobalLock(pool, UUID.randomUUID()) {
    override protected val LEASE_LENGTH_MS: Int = 1000
    override protected val CHECK_TIME_MS: Int = 100
    override protected val RENEW_BUFFER_MS: Int = 500
  }

  def pool = new TestConnectionPool()

  override def afterEach(): Unit = {
    pool.withConnection { connection =>
      val statement = connection.createStatement()
      statement.execute("DELETE FROM process_definition")
      statement.execute("DELETE FROM task_trigger_request")
      statement.execute("DELETE FROM process_trigger_request")
      statement.execute("DELETE FROM sundial_lock_lease")
      connection.commit()
    }
  }

  "PostgresTaskLogsDao" must {
    "save an event" in pool.withConnection { implicit conn =>
      val dao = new PostgresTaskLogsDao()
      val event = TaskEventLog(UUID.randomUUID(), UUID.randomUUID(), new Date(), "lorem", "ipsum")
      dao.saveEvents(Seq(event))
      dao.loadEventsForTask(event.taskId) mustBe(Seq(event))
      conn.commit()
    }
  }

  "PostgresProcessDao" must {
    "save a process" in pool.withConnection { implicit conn =>
      val dao = new PostgresProcessDao()
      val process = model.Process(UUID.randomUUID(), "someProcess", new Date(), ProcessStatus.Running(), Option.empty)
      dao.saveProcess(process)
      dao.loadProcess(process.id) mustBe(Some(process))
      conn.commit()
    }

    "load None when a process doesn't exist" in pool.withConnection { implicit conn =>
      val dao = new PostgresProcessDao()
      dao.loadProcess(UUID.randomUUID()) mustBe(None)
      conn.commit()
    }

    "save process status correctly" in pool.withConnection { implicit conn =>
      val dao = new PostgresProcessDao()
      val exemplars = Seq(
        model.Process(UUID.randomUUID(), "someProcess", new Date(), ProcessStatus.Running(), Option.empty),
        model.Process(UUID.randomUUID(), "someProcess", new Date(), ProcessStatus.Succeeded(new Date()), Option.empty),
        model.Process(UUID.randomUUID(), "someProcess", new Date(), ProcessStatus.Failed(new Date()), Option.empty)
      )
      exemplars.map { process =>
        dao.saveProcess(process)
        dao.loadProcess(process.id) mustBe(Some(process))
      }
      conn.commit()
    }

    "save task filter correctly" in pool.withConnection { implicit conn =>
      val dao = new PostgresProcessDao()
      val exemplars = Seq(
        model.Process(UUID.randomUUID(), "someProcess", new Date(), ProcessStatus.Running(), Option.empty),
        model.Process(UUID.randomUUID(), "someProcess", new Date(), ProcessStatus.Running(), Some(Seq())),
        model.Process(UUID.randomUUID(), "someProcess", new Date(), ProcessStatus.Running(), Some(Seq("other")))
      )
      exemplars.map { process =>
        dao.saveProcess(process)
        dao.loadProcess(process.id) mustBe(Some(process))
      }
      conn.commit()
    }

    "update a process correctly" in pool.withConnection { implicit conn =>
      val dao = new PostgresProcessDao()
      val process = model.Process(UUID.randomUUID(), "someProcess", new Date(), ProcessStatus.Running(), Option.empty)
      dao.saveProcess(process)
      dao.loadProcess(process.id) mustBe(Some(process))
      val updated = process.copy(status = ProcessStatus.Succeeded(new Date()))
      dao.saveProcess(updated)
      dao.loadProcess(updated.id) mustBe(Some(updated))
      conn.commit()
    }

    "save a task" in pool.withConnection { implicit conn =>
      val dao = new PostgresProcessDao()
      val task = model.Task(UUID.randomUUID(), UUID.randomUUID(), "someProcess", "someTask",
                            ShellCommandExecutable("echo 'hello world'", Map.empty),
                            0, new Date(), TaskStatus.Running())
      dao.saveTask(task)
      dao.loadTask(task.id) mustBe(Some(task))
      conn.commit()
    }

    "save task status correctly" in pool.withConnection { implicit conn =>
      val dao = new PostgresProcessDao()
      val statuses = Seq(TaskStatus.Running(), TaskStatus.Success(new Date()), TaskStatus.Failure(new Date(), Some("test reason")))
      statuses.map { status =>
        val task = model.Task(UUID.randomUUID(), UUID.randomUUID(), "someProcess", "someTask",
                              ShellCommandExecutable("echo 'hello world'", Map.empty),
                              0, new Date(), status)
        dao.saveTask(task)
        dao.loadTask(task.id) mustBe(Some(task))
      }
      conn.commit()
    }

    "load None when a task doesn't exist" in pool.withConnection { implicit conn =>
      val dao = new PostgresProcessDao()
      dao.loadTask(UUID.randomUUID()) mustBe(None)
      conn.commit()
    }

    "save task executables correctly" in pool.withConnection { implicit conn =>
      val dao = new PostgresProcessDao()
      val executables = Seq(
        ShellCommandExecutable("echo 'hello world'", Map.empty),
        ShellCommandExecutable("echo 'hello world'", Map("key" -> "value")),
        ContainerServiceExecutable("ubuntu", "latest", Seq.empty, None, None, Seq.empty, Map.empty),
        ContainerServiceExecutable("ubuntu", "latest", Seq("sleep", "1000"), Some(10), Some(1000), Seq("log/application.log"), Map("test" -> "value"))
      )
      executables.map { executable =>
        val task = model.Task(UUID.randomUUID(), UUID.randomUUID(), "someProcess", "someTask",
          executable,
          0, new Date(), TaskStatus.Running())
        dao.saveTask(task)
        dao.loadTask(task.id) mustBe(Some(task))
      }
      conn.commit()
    }

    "update a task correctly" in pool.withConnection { implicit conn =>
      val dao = new PostgresProcessDao()
      val task = model.Task(UUID.randomUUID(), UUID.randomUUID(), "someProcess", "someTask",
                            ShellCommandExecutable("echo 'hello world'", Map.empty),
                            0, new Date(), TaskStatus.Running())
      dao.saveTask(task)
      dao.loadTask(task.id) mustBe(Some(task))
      val updated = task.copy(status = TaskStatus.Success(new Date()))
      dao.saveTask(updated)
      dao.loadTask(updated.id) mustBe(Some(updated))
      conn.commit()
    }

    "save a task status correctly" in pool.withConnection { implicit conn =>
      val dao = new PostgresProcessDao()
      val reportedTaskStatus = ReportedTaskStatus(UUID.randomUUID(), TaskStatus.Success(new Date()))
      dao.saveReportedTaskStatus(reportedTaskStatus) mustBe(true)
      dao.findReportedTaskStatus(reportedTaskStatus.taskId) mustBe(Some(reportedTaskStatus))
      conn.commit()
    }

    "not save a task status more than once" in pool.withConnection { implicit conn =>
      val dao = new PostgresProcessDao()
      val reportedTaskStatus = ReportedTaskStatus(UUID.randomUUID(), TaskStatus.Success(new Date()))
      dao.saveReportedTaskStatus(reportedTaskStatus) mustBe(true)
      dao.saveReportedTaskStatus(reportedTaskStatus) mustBe(false)
      // make sure it doesn't overwrite
      dao.saveReportedTaskStatus(reportedTaskStatus.copy(status = TaskStatus.Failure(new Date(), Some("test reason")))) mustBe(false)
      dao.findReportedTaskStatus(reportedTaskStatus.taskId) mustBe(Some(reportedTaskStatus))
      conn.commit()
    }
  }

  "PostgresProcessDefinitionDao" must {
    "save a process definition correctly" in pool.withConnection { implicit conn =>
      val dao = new PostgresProcessDefinitionDao()
      val definition = ProcessDefinition(
        "someDefinition",
        Some("someDescription"),
        Some(ContinuousSchedule(0)),
        ProcessOverlapAction.Wait,
        Seq.empty,
        new Date(),
        false
      )
      dao.saveProcessDefinition(definition)
      dao.loadProcessDefinition(definition.name) mustBe(Some(definition))
      conn.commit()
    }

    "load None when a process definition doesn't exist" in pool.withConnection { implicit conn =>
      val dao = new PostgresProcessDefinitionDao()
      dao.loadProcessDefinition("someDefinition") mustBe(None)
      conn.commit()
    }

    "update a process definition correctly" in pool.withConnection { implicit conn =>
      val dao = new PostgresProcessDefinitionDao()
      val definition = ProcessDefinition(
        "someDefinition",
        Some("someDescription"),
        Some(ContinuousSchedule(0)),
        ProcessOverlapAction.Wait,
        Seq.empty,
        new Date(),
        false
      )
      dao.saveProcessDefinition(definition)
      dao.loadProcessDefinition(definition.name) mustBe(Some(definition))

      val updated = definition.copy(schedule = None,
                                    overlapAction = ProcessOverlapAction.Terminate,
                                    description = None,
                                    isPaused = true)
      dao.saveProcessDefinition(updated)
      dao.loadProcessDefinition(definition.name) mustBe(Some(updated))
      conn.commit()
    }

    "delete a process definition correctly" in pool.withConnection { implicit conn =>
      val dao = new PostgresProcessDefinitionDao()
      val definition = ProcessDefinition(
        "someDefinition",
        Some("someDescription"),
        Some(ContinuousSchedule(0)),
        ProcessOverlapAction.Wait,
        Seq.empty,
        new Date(),
        false
      )
      dao.saveProcessDefinition(definition)
      dao.loadProcessDefinition(definition.name) mustBe(Some(definition))
      dao.deleteProcessDefinition(definition.name) mustBe(true)
      dao.loadProcessDefinition(definition.name) mustBe(None)
      dao.deleteProcessDefinition(definition.name) mustBe(false)
      conn.commit()
    }

    "save a task definition correctly" in pool.withConnection { implicit conn =>
      val dao = new PostgresProcessDefinitionDao()
      val processId = UUID.randomUUID()
      val definition = TaskDefinition(
        "someTask",
        processId,
        ShellCommandExecutable("echo hello", Map.empty),
        TaskLimits(10, None),
        TaskBackoff(5, 1),
        TaskDependencies(Seq.empty, Seq.empty),
        true
      )
      dao.saveTaskDefinition(definition)
      dao.loadTaskDefinitions(definition.processId) mustBe(Seq(definition))
      conn.commit()
    }
  }

  "PostgresTaskMetadataDao" must {
    "save task metadata correctly" in pool.withConnection { implicit conn =>
      val dao = new PostgresTaskMetadataDao()
      val taskId = UUID.randomUUID()
      val entries = Seq(
        TaskMetadataEntry(UUID.randomUUID(), taskId, new Date(), "key1", "value1"),
        TaskMetadataEntry(UUID.randomUUID(), taskId, new Date(), "key2", "value2"),
        TaskMetadataEntry(UUID.randomUUID(), taskId, new Date(), "key3", "value3")
      )
      dao.saveMetadataEntries(entries)
      dao.loadMetadataForTask(taskId) mustBe(entries)
      conn.commit()
    }

    "load an empty Seq when there's no metadata" in pool.withConnection { implicit conn =>
      val dao = new PostgresTaskMetadataDao()
      dao.loadMetadataForTask(UUID.randomUUID()) mustBe(Seq.empty)
      conn.commit()
    }
  }

  "PostgresTriggerDao" must {
    "save and load a task trigger request correctly" in pool.withConnection { implicit conn =>
      val dao = new PostgresTriggerDao()
      val request = TaskTriggerRequest(UUID.randomUUID(), "someProcess", "someTask", new Date(), None)
      dao.saveTaskTriggerRequest(request)
      dao.loadOpenTaskTriggerRequests() mustBe(Seq(request))
      dao.loadOpenTaskTriggerRequests(Some(request.processDefinitionName)) mustBe(Seq(request))
      conn.commit()
    }

    "close a task trigger request correctly" in pool.withConnection { implicit conn =>
      val dao = new PostgresTriggerDao()
      val request = TaskTriggerRequest(UUID.randomUUID(), "someProcess", "someTask", new Date(), None)
      dao.saveTaskTriggerRequest(request)
      dao.loadOpenTaskTriggerRequests() mustBe(Seq(request))
      val updated = request.copy(startedProcessId = Some(UUID.randomUUID()))
      dao.saveTaskTriggerRequest(updated)
      dao.loadOpenTaskTriggerRequests() mustBe(Seq.empty)
      conn.commit()
    }

    "save and load a process trigger request correctly" in pool.withConnection { implicit conn =>
      val dao = new PostgresTriggerDao()
      val request = ProcessTriggerRequest(UUID.randomUUID(), "someProcess", new Date(), Some(Seq("someTask")), None)
      dao.saveProcessTriggerRequest(request)
      dao.loadOpenProcessTriggerRequests() mustBe(Seq(request))
      dao.loadOpenProcessTriggerRequests(Some(request.processDefinitionName)) mustBe(Seq(request))
      conn.commit()
    }

    "close a task process request correctly" in pool.withConnection { implicit conn =>
      val dao = new PostgresTriggerDao()
      val request = ProcessTriggerRequest(UUID.randomUUID(), "someProcess", new Date(), None, None)
      dao.saveProcessTriggerRequest(request)
      dao.loadOpenProcessTriggerRequests() mustBe(Seq(request))
      val updated = request.copy(startedProcessId = Some(UUID.randomUUID()))
      dao.saveProcessTriggerRequest(updated)
      dao.loadOpenProcessTriggerRequests() mustBe(Seq.empty)
      conn.commit()
    }

    "save and load a kill request correctly" in pool.withConnection { implicit conn =>
      val dao = new PostgresTriggerDao()
      val request = KillProcessRequest(UUID.randomUUID(), UUID.randomUUID(), new Date())
      dao.saveKillProcessRequest(request)
      dao.loadKillProcessRequests(request.processId) mustBe(Seq(request))
      conn.commit()
    }
  }

  "PostgresShellCommandStateDao" must {
    "save and load a shell command state correctly" in pool.withConnection { implicit conn =>
      val dao = new PostgresShellCommandStateDao()
      val state = new ShellCommandState(UUID.randomUUID(), new Date(), TaskExecutorStatus.Initializing)
      dao.saveState(state)
      dao.loadState(state.taskId) mustBe(Some(state))
      conn.commit()
    }

    "update a shell command state correctly" in pool.withConnection { implicit conn =>
      val dao = new PostgresShellCommandStateDao()
      val state = new ShellCommandState(UUID.randomUUID(), new Date(), TaskExecutorStatus.Initializing)
      dao.saveState(state)
      dao.loadState(state.taskId) mustBe(Some(state))
      val updated = state.copy(status = TaskExecutorStatus.Running)
      dao.saveState(updated)
      dao.loadState(state.taskId) mustBe(Some(updated))
      conn.commit()
    }
  }

  "PostgresContainerServiceStateDao" must {
    "save and load a container service state correctly" in pool.withConnection { implicit conn =>
      val dao = new PostgresContainerServiceStateDao()
      val state = new ContainerServiceState(UUID.randomUUID(), new Date(), "some::task::arn", TaskExecutorStatus.Initializing)
      dao.saveState(state)
      dao.loadState(state.taskId) mustBe(Some(state))
      conn.commit()
    }

    "update a container service state correctly" in pool.withConnection { implicit conn =>
      val dao = new PostgresContainerServiceStateDao()
      val state = new ContainerServiceState(UUID.randomUUID(), new Date(), "some::task::arn", TaskExecutorStatus.Initializing)
      dao.saveState(state)
      dao.loadState(state.taskId) mustBe(Some(state))
      val updated = state.copy(status = TaskExecutorStatus.Running)
      dao.saveState(updated)
      dao.loadState(state.taskId) mustBe(Some(updated))
      conn.commit()
    }
  }

  "PostgresGlobalLock" must {
    "acquire a lease and execute a function" in {
      val lock = makeTestLock()
      lock.executeGuarded() { Thread.sleep(100) }

      lock.metrics.values() mustBe(GlobalLockMetricValues(1, 0, 1, 0, 0, 1))
    }

    "acquire a lease renew it" in {
      val lock = makeTestLock()
      lock.executeGuarded() { Thread.sleep(1000) }

      lock.metrics.values() mustBe(GlobalLockMetricValues(1, 0, 2, 0, 0, 1))
    }
  }
}
