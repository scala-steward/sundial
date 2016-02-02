import java.util.{UUID, Date}
import java.util.concurrent.TimeUnit

import com.typesafe.config.ConfigFactory
import common.{SundialGlobal, Bootstrap, TestDependencies}
import dao.memory.InMemorySundialDao
import model._
import org.junit.runner.RunWith
import org.scalatest.{ShouldMatchers, FlatSpec}
import org.scalatestplus.play.{PlaySpec, OneAppPerSuite}
import org.specs2.Specification
import org.specs2.runner.JUnitRunner
import play.api.{GlobalSettings, Configuration}
import play.api.test.FakeApplication
import service.MetricValues

class SundialSchedulingSpec extends PlaySpec with OneAppPerSuite {

  //
  // UTILITY FUNCTIONS
  //

  override implicit lazy val app: FakeApplication = new FakeApplication(
      withGlobal = Some(new GlobalSettings() {
        override def configuration: Configuration = {
          val testConfig = ConfigFactory.load("application.test.conf")
          Configuration(testConfig)
        }
      })) {
    override def configuration: Configuration = {
      val testConfig = ConfigFactory.load("application.test.conf")
      Configuration(testConfig)
    }
  }

  def ambiguousShellScript(delayMs: Int) = {
    val script =
      s"""
         |sleep ${delayMs/1000.0}
       """.stripMargin
    ShellCommandExecutable(script, Map.empty)
  }

  def shellScript(delayMs: Int, succeed: Boolean) = {
    val script =
      s"""
         |sleep ${delayMs/1000.0}
         |echo "**status=${if(succeed) "success" else "failure"}"
       """.stripMargin
    ShellCommandExecutable(script, Map.empty)
  }

  def retryableShellScript(delayMs: Int, failures: Int) = {
    val script =
      s"""
         |sleep ${delayMs/1000.0}
         |if [ "$$SUNDIAL_TASK_PREVIOUS_ATTEMPTS" = "$failures" ]
         |then
         |  echo "**status=success"
         |else
         |  echo "**status=failure"
         |fi
       """.stripMargin
    ShellCommandExecutable(script, Map.empty)
  }

  def testProcessDef(disabled: Boolean = false, overlapAction: ProcessOverlapAction = ProcessOverlapAction.Wait, processSchedule: ProcessSchedule = model.ContinuousSchedule(bufferSeconds = 0))(implicit dao: InMemorySundialDao) = {
    dao.processDefinitionDao.saveProcessDefinition(ProcessDefinition(
      "test" + (Math.random()*100000).toInt,
      Some("someDescription"),
      Some(processSchedule),
      overlapAction,
      Seq.empty,
      new Date(new Date().getTime - TimeUnit.MINUTES.toSeconds(10)),
      disabled
    ))
  }

  def testTaskDef(exec: Executable,
                  requires: Seq[TaskDefinitionTemplate] = Seq.empty,
                  optional: Seq[TaskDefinitionTemplate] = Seq.empty,
                  maxAttempts: Int = 1,
                  requireExplicitSuccess: Boolean = true)
                 (implicit processDef: ProcessDefinition, dao: InMemorySundialDao) = {
    dao.processDefinitionDao.saveTaskDefinitionTemplate(TaskDefinitionTemplate(
      "task" + (Math.random()*100000).toInt,
      processDef.name,
      exec,
      TaskLimits(maxAttempts, None),
      TaskBackoff(0),
      TaskDependencies(requires.map(_.name), optional.map(_.name)),
      requireExplicitSuccess
    ))
  }

  def processHistory()(implicit processDef: ProcessDefinition, dao: InMemorySundialDao) = {
    dao.processDao.allProcesses()
      .filter(_.processDefinitionName == processDef.name)
      .sortBy(_.startedAt)
      .map(_.status)
      .map {
        case ProcessStatus.Running() => Option.empty[Boolean]
        case ProcessStatus.Succeeded(_) => Some(true)
        case ProcessStatus.Failed(_) => Some(false)
      }
  }

  def taskHistory(taskDefTemplate: TaskDefinitionTemplate)(implicit dao: InMemorySundialDao) = {
    dao.processDao.allProcesses()
      .filter(_.processDefinitionName == taskDefTemplate.processDefinitionName)
      .flatMap { process =>
        dao.processDao.loadTasksForProcess(process.id)
      }
      .filter(_.taskDefinitionName == taskDefTemplate.name)
      .sortBy(_.startedAt)
      .map(_.status)
      .map {
        case TaskStatus.Running() => Option.empty[Boolean]
        case TaskStatus.Success(_) => Some(true)
        case TaskStatus.Failure(_, _) => Some(false)
      }
  }

  //
  // TEST CASES
  //

  "Sundial" must {
    "run a single task that succeeds" in {
      implicit val dependencies = new TestDependencies()
      implicit val dao = dependencies.daoFactory.buildSundialDao()
      implicit val sundial = Bootstrap.bootstrapSundial(dependencies)
      implicit val processDef = testProcessDef()
      val taskDef = testTaskDef(shellScript(500, true))

      sundial.doWork()
      sundial.metrics.values mustBe(MetricValues(1, 1, 1, 1, 0, 0))
      // wait until it has finished
      Thread.sleep(800)
      sundial.doWork()
      sundial.metrics.values mustBe(MetricValues(2, 2, 1, 1, 1, 0))

      processHistory() mustBe(Seq(Some(true)))
      taskHistory(taskDef) mustBe(Seq(Some(true)))
    }

    "wait for the current process to finish when overlap action is \"wait\"" in {
      implicit val dependencies = new TestDependencies()
      implicit val dao = dependencies.daoFactory.buildSundialDao()
      implicit val sundial = Bootstrap.bootstrapSundial(dependencies)
      implicit val processDef = testProcessDef(overlapAction = ProcessOverlapAction.Wait)
      val taskDef = testTaskDef(shellScript(500, true))

      sundial.doWork()
      sundial.metrics.values mustBe (MetricValues(1, 1, 1, 1, 0, 0))
      // We should step the process again given it's still
      // running, but we won't start it again though
      sundial.doWork()
      sundial.metrics.values mustBe (MetricValues(2, 2, 1, 1, 0, 0))
      // wait until it has finished
      Thread.sleep(800)
      sundial.doWork()
      sundial.metrics.values mustBe (MetricValues(3, 3, 1, 1, 1, 0))

      processHistory() mustBe (Seq(Some(true)))
      taskHistory(taskDef) mustBe (Seq(Some(true)))
    }

    "terminate the current process and start a new one when overlap action is \"terminate\" and process definition is scheduled to run again" in {
      val dependencies = new TestDependencies()
      implicit val dao = dependencies.daoFactory.buildSundialDao()
      implicit val processDefinition = testProcessDef(overlapAction = ProcessOverlapAction.Terminate)
      val taskDef = testTaskDef(shellScript(500, true))

      val sundial = Bootstrap.bootstrapSundial(dependencies)
      // First scheduling cycle...
      sundial.doWork()
      sundial.metrics.values mustBe (MetricValues(1, 1, 1, 1, 0, 0))

      // When the next scheduling happens, there will be
      // an overlap with the process definition, so
      // the current running process for that definition
      // will be killed and another one started
      sundial.doWork()
      sundial.metrics.values mustBe (MetricValues(2, 4, 2, 2, 0, 1))

      // wait until process has finished
      Thread.sleep(800)
      // One process should've been finished
      sundial.doWork()
      sundial.metrics.values mustBe (MetricValues(3, 5, 2, 2, 1, 1))

      processHistory() mustBe (Seq(Some(false), Some(true)))
      taskHistory(taskDef) mustBe (Seq(Some(false), Some(true)))
    }

    "terminate the current process and start a new one when overlap action is \"terminate\" and process definition has been manually triggered to run again" in {
      val dependencies = new TestDependencies()
      implicit val dao = dependencies.daoFactory.buildSundialDao()
      implicit val processDefinition = testProcessDef(overlapAction = ProcessOverlapAction.Terminate, processSchedule = model.ContinuousSchedule(0))
      val taskDef = testTaskDef(shellScript(500, true))

      val sundial = Bootstrap.bootstrapSundial(dependencies)
      // First scheduling cycle...
      sundial.doWork()
      sundial.metrics.values mustBe (MetricValues(1, 1, 1, 1, 0, 0))

      // Let's create a manual trigger for the process definition...
      dao.triggerDao.saveProcessTriggerRequest(ProcessTriggerRequest(UUID.randomUUID(), processDefinition.name, new Date(), None, None))
      // ...but first, let's change the schedule so the process definition
      // won't be scheduled to run for another while
      dao.processDefinitionDao.saveProcessDefinition(processDefinition.copy(schedule = Some(model.ContinuousSchedule(300))))

      // When the next scheduling happens, there will be
      // an overlap with the process definition, so
      // the current running process for that definition
      // will be killed and another one started
      sundial.doWork()
      sundial.metrics.values mustBe (MetricValues(2, 4, 2, 2, 0, 1))

      // wait until process has finished
      Thread.sleep(800)
      // One process should've been finished
      sundial.doWork()
      sundial.metrics.values mustBe (MetricValues(3, 5, 2, 2, 1, 1))

      processHistory() mustBe (Seq(Some(false), Some(true)))
      taskHistory(taskDef) mustBe (Seq(Some(false), Some(true)))
    }

    "run a single task that fails" in {
      implicit val dependencies = new TestDependencies()
      implicit val dao = dependencies.daoFactory.buildSundialDao()
      implicit val sundial = Bootstrap.bootstrapSundial(dependencies)
      implicit val processDef = testProcessDef()
      val taskDef = testTaskDef(shellScript(500, false))

      sundial.doWork()
      sundial.metrics.values mustBe(MetricValues(1, 1, 1, 1, 0, 0))
      sundial.doWork()
      sundial.metrics.values mustBe(MetricValues(2, 2, 1, 1, 0, 0))
      // wait until it has finished
      Thread.sleep(800)
      sundial.doWork()
      sundial.metrics.values mustBe(MetricValues(3, 3, 1, 1, 1, 0))

      processHistory() mustBe(Seq(Some(false)))
      taskHistory(taskDef) mustBe(Seq(Some(false)))
    }

    "run a single task that fails and retries" in {
      implicit val dependencies = new TestDependencies()
      implicit val dao = dependencies.daoFactory.buildSundialDao()
      implicit val sundial = Bootstrap.bootstrapSundial(dependencies)
      implicit val processDef = testProcessDef()
      val taskDef = testTaskDef(retryableShellScript(500, 1), maxAttempts = 2)

      sundial.doWork()
      sundial.metrics.values mustBe(MetricValues(1, 1, 1, 1, 0, 0))
      // wait until it has finished
      Thread.sleep(800)
      sundial.doWork()
      sundial.metrics.values mustBe(MetricValues(2, 2, 1, 2, 0, 0))
      sundial.doWork()
      sundial.metrics.values mustBe(MetricValues(3, 3, 1, 2, 0, 0))
      Thread.sleep(800)
      sundial.doWork()
      sundial.metrics.values mustBe(MetricValues(4, 4, 1, 2, 1, 0))

      processHistory() mustBe(Seq(Some(true)))
      taskHistory(taskDef) mustBe(Seq(Some(false), Some(true)))
    }

    "run a sequence of successful tasks serially" in {
      implicit val dependencies = new TestDependencies()
      implicit val dao = dependencies.daoFactory.buildSundialDao()
      implicit val sundial = Bootstrap.bootstrapSundial(dependencies)
      implicit val processDef = testProcessDef()
      val taskDef1 = testTaskDef(shellScript(500, true))
      val taskDef2 = testTaskDef(shellScript(500, true), requires = Seq(taskDef1))
      val taskDef3 = testTaskDef(shellScript(500, true), requires = Seq(taskDef2))

      sundial.doWork() // this should just start task 1
      sundial.metrics.values mustBe(MetricValues(1, 1, 1, 1, 0, 0))
      sundial.doWork() // should do nothing
      sundial.metrics.values mustBe(MetricValues(2, 2, 1, 1, 0, 0))

      processHistory() mustBe(Seq(None))
      taskHistory(taskDef1) mustBe(Seq(None))
      taskHistory(taskDef2) mustBe(Seq())
      taskHistory(taskDef3) mustBe(Seq())
      Thread.sleep(800)

      sundial.doWork() // we should mark task 1 done, start task 2
      sundial.metrics.values mustBe(MetricValues(3, 3, 1, 2, 0, 0))
      sundial.doWork() // should do nothing
      sundial.metrics.values mustBe(MetricValues(4, 4, 1, 2, 0, 0))

      processHistory() mustBe(Seq(None))
      taskHistory(taskDef1) mustBe(Seq(Some(true)))
      taskHistory(taskDef2) mustBe(Seq(None))
      taskHistory(taskDef3) mustBe(Seq())
      Thread.sleep(800)

      sundial.doWork() // we should mark task 2 done, start task 3
      sundial.metrics.values mustBe(MetricValues(5, 5, 1, 3, 0, 0))
      sundial.doWork() // should do nothing
      sundial.metrics.values mustBe(MetricValues(6, 6, 1, 3, 0, 0))

      processHistory() mustBe(Seq(None))
      taskHistory(taskDef1) mustBe(Seq(Some(true)))
      taskHistory(taskDef2) mustBe(Seq(Some(true)))
      taskHistory(taskDef3) mustBe(Seq(None))
      Thread.sleep(800)

      sundial.doWork() // we should mark task 3 done and the mark the process done
      sundial.metrics.values mustBe(MetricValues(7, 7, 1, 3, 1, 0))

      processHistory() mustBe(Seq(Some(true)))
      taskHistory(taskDef1) mustBe(Seq(Some(true)))
      taskHistory(taskDef2) mustBe(Seq(Some(true)))
      taskHistory(taskDef3) mustBe(Seq(Some(true)))
    }

    "run a set of unrelated tasks in parallel" in {
      implicit val dependencies = new TestDependencies()
      implicit val dao = dependencies.daoFactory.buildSundialDao()
      implicit val sundial = Bootstrap.bootstrapSundial(dependencies)
      implicit val processDef = testProcessDef()
      val taskDef1 = testTaskDef(shellScript(500, true))
      val taskDef2 = testTaskDef(shellScript(500, true))
      val taskDef3 = testTaskDef(shellScript(500, true))

      sundial.doWork() // this should start all the tasks
      sundial.metrics.values mustBe(MetricValues(1, 1, 1, 3, 0, 0))
      sundial.doWork() // should do nothing
      sundial.metrics.values mustBe(MetricValues(2, 2, 1, 3, 0, 0))

      processHistory() mustBe(Seq(None))
      taskHistory(taskDef1) mustBe(Seq(None))
      taskHistory(taskDef2) mustBe(Seq(None))
      taskHistory(taskDef3) mustBe(Seq(None))
      Thread.sleep(800)

      sundial.doWork() // this should mark all tasks done and mark the process done
      sundial.metrics.values mustBe(MetricValues(3, 3, 1, 3, 1, 0))

      processHistory() mustBe(Seq(Some(true)))
      taskHistory(taskDef1) mustBe(Seq(Some(true)))
      taskHistory(taskDef2) mustBe(Seq(Some(true)))
      taskHistory(taskDef3) mustBe(Seq(Some(true)))
    }

    "run a simple diamond hierarchy in the correct order" in {
      implicit val dependencies = new TestDependencies()
      implicit val dao = dependencies.daoFactory.buildSundialDao()
      implicit val sundial = Bootstrap.bootstrapSundial(dependencies)
      implicit val processDef = testProcessDef()
      val taskDef1 = testTaskDef(shellScript(500, true))
      val taskDef2 = testTaskDef(shellScript(500, true), requires = Seq(taskDef1))
      val taskDef3 = testTaskDef(shellScript(1200, true), requires = Seq(taskDef1))
      val taskDef4 = testTaskDef(shellScript(500, true), requires = Seq(taskDef2, taskDef3))

      sundial.doWork() // this should start task 1
      sundial.metrics.values mustBe(MetricValues(1, 1, 1, 1, 0, 0))
      sundial.doWork() // should do nothing
      sundial.metrics.values mustBe(MetricValues(2, 2, 1, 1, 0, 0))

      processHistory() mustBe(Seq(None))
      taskHistory(taskDef1) mustBe(Seq(None))
      taskHistory(taskDef2) mustBe(Seq())
      taskHistory(taskDef3) mustBe(Seq())
      taskHistory(taskDef4) mustBe(Seq())
      Thread.sleep(800)

      sundial.doWork() // this should mark task 1 done and start tasks 2 and 3
      sundial.metrics.values mustBe(MetricValues(3, 3, 1, 3, 0, 0))
      sundial.doWork() // should do nothing
      sundial.metrics.values mustBe(MetricValues(4, 4, 1, 3, 0, 0))

      processHistory() mustBe(Seq(None))
      taskHistory(taskDef1) mustBe(Seq(Some(true)))
      taskHistory(taskDef2) mustBe(Seq(None))
      taskHistory(taskDef3) mustBe(Seq(None))
      taskHistory(taskDef4) mustBe(Seq())
      Thread.sleep(800)

      sundial.doWork() // this should mark task 2 done, but task 3 should still be running
      sundial.metrics.values mustBe(MetricValues(5, 5, 1, 3, 0, 0))
      sundial.doWork() // should do nothing
      sundial.metrics.values mustBe(MetricValues(6, 6, 1, 3, 0, 0))

      processHistory() mustBe(Seq(None))
      taskHistory(taskDef1) mustBe(Seq(Some(true)))
      taskHistory(taskDef2) mustBe(Seq(Some(true)))
      taskHistory(taskDef3) mustBe(Seq(None))
      taskHistory(taskDef4) mustBe(Seq())
      Thread.sleep(800)

      sundial.doWork() // this should mark task 3 done and start task 4
      sundial.metrics.values mustBe(MetricValues(7, 7, 1, 4, 0, 0))
      sundial.doWork() // should do nothing
      sundial.metrics.values mustBe(MetricValues(8, 8, 1, 4, 0, 0))

      processHistory() mustBe(Seq(None))
      taskHistory(taskDef1) mustBe(Seq(Some(true)))
      taskHistory(taskDef2) mustBe(Seq(Some(true)))
      taskHistory(taskDef3) mustBe(Seq(Some(true)))
      taskHistory(taskDef4) mustBe(Seq(None))
      Thread.sleep(800)

      sundial.doWork() // this should mark task 4 done and mark the process done
      sundial.metrics.values mustBe(MetricValues(9, 9, 1, 4, 1, 0))

      processHistory() mustBe(Seq(Some(true)))
      taskHistory(taskDef1) mustBe(Seq(Some(true)))
      taskHistory(taskDef2) mustBe(Seq(Some(true)))
      taskHistory(taskDef3) mustBe(Seq(Some(true)))
      taskHistory(taskDef4) mustBe(Seq(Some(true)))
    }

    "run a simple diamond hierarchy in the correct order with a failure" in {
      implicit val dependencies = new TestDependencies()
      implicit val dao = dependencies.daoFactory.buildSundialDao()
      implicit val sundial = Bootstrap.bootstrapSundial(dependencies)
      implicit val processDef = testProcessDef()
      val taskDef1 = testTaskDef(shellScript(500, true))
      val taskDef2 = testTaskDef(shellScript(500, true), requires = Seq(taskDef1))
      val taskDef3 = testTaskDef(shellScript(1200, false), requires = Seq(taskDef1))
      val taskDef4 = testTaskDef(shellScript(500, true), requires = Seq(taskDef2, taskDef3))

      sundial.doWork() // this should start task 1
      sundial.metrics.values mustBe(MetricValues(1, 1, 1, 1, 0, 0))
      sundial.doWork() // should do nothing
      sundial.metrics.values mustBe(MetricValues(2, 2, 1, 1, 0, 0))

      processHistory() mustBe(Seq(None))
      taskHistory(taskDef1) mustBe(Seq(None))
      taskHistory(taskDef2) mustBe(Seq())
      taskHistory(taskDef3) mustBe(Seq())
      taskHistory(taskDef4) mustBe(Seq())
      Thread.sleep(800)

      sundial.doWork() // this should mark task 1 done and start tasks 2 and 3
      sundial.metrics.values mustBe(MetricValues(3, 3, 1, 3, 0, 0))
      sundial.doWork() // should do nothing
      sundial.metrics.values mustBe(MetricValues(4, 4, 1, 3, 0, 0))

      processHistory() mustBe(Seq(None))
      taskHistory(taskDef1) mustBe(Seq(Some(true)))
      taskHistory(taskDef2) mustBe(Seq(None))
      taskHistory(taskDef3) mustBe(Seq(None))
      taskHistory(taskDef4) mustBe(Seq())
      Thread.sleep(800)

      sundial.doWork() // this should mark task 2 done, but task 3 should still be running
      sundial.metrics.values mustBe(MetricValues(5, 5, 1, 3, 0, 0))
      sundial.doWork() // should do nothing
      sundial.metrics.values mustBe(MetricValues(6, 6, 1, 3, 0, 0))

      processHistory() mustBe(Seq(None))
      taskHistory(taskDef1) mustBe(Seq(Some(true)))
      taskHistory(taskDef2) mustBe(Seq(Some(true)))
      taskHistory(taskDef3) mustBe(Seq(None))
      taskHistory(taskDef4) mustBe(Seq())
      Thread.sleep(800)

      sundial.doWork() // this should mark task 3 failed and mark the process failed
      sundial.metrics.values mustBe(MetricValues(7, 7, 1, 3, 1, 0))

      processHistory() mustBe(Seq(Some(false)))
      taskHistory(taskDef1) mustBe(Seq(Some(true)))
      taskHistory(taskDef2) mustBe(Seq(Some(true)))
      taskHistory(taskDef3) mustBe(Seq(Some(false)))
      taskHistory(taskDef4) mustBe(Seq())
    }

    "fail a task that requires explicit success when no success is found" in {
      implicit val dependencies = new TestDependencies()
      implicit val dao = dependencies.daoFactory.buildSundialDao()
      implicit val sundial = Bootstrap.bootstrapSundial(dependencies)
      implicit val processDef = testProcessDef()
      val taskDef = testTaskDef(ambiguousShellScript(500), requireExplicitSuccess = true)

      sundial.doWork() // should start the task
      sundial.metrics.values mustBe(MetricValues(1, 1, 1, 1, 0, 0))
      sundial.doWork() // should do nothing
      sundial.metrics.values mustBe(MetricValues(2, 2, 1, 1, 0, 0))
      Thread.sleep(800)

      sundial.doWork() // should mark the task as failed and process as failed
      sundial.metrics.values mustBe(MetricValues(3, 3, 1, 1, 1, 0))

      processHistory() mustBe(Seq(Some(false)))
      taskHistory(taskDef) mustBe(Seq(Some(false)))
    }

    "succeed a task that does not require explicit success when no success is found" in {
      implicit val dependencies = new TestDependencies()
      implicit val dao = dependencies.daoFactory.buildSundialDao()
      implicit val sundial = Bootstrap.bootstrapSundial(dependencies)
      implicit val processDef = testProcessDef()
      val taskDef = testTaskDef(ambiguousShellScript(500), requireExplicitSuccess = false)

      sundial.doWork() // should start the task
      sundial.metrics.values mustBe(MetricValues(1, 1, 1, 1, 0, 0))
      sundial.doWork() // should do nothing
      sundial.metrics.values mustBe(MetricValues(2, 2, 1, 1, 0, 0))
      Thread.sleep(800)

      sundial.doWork() // should mark the task as completed and process as completed
      sundial.metrics.values mustBe(MetricValues(3, 3, 1, 1, 1, 0))

      processHistory() mustBe(Seq(Some(true)))
      taskHistory(taskDef) mustBe(Seq(Some(true)))
    }

    "not run a disabled process" in {
      implicit val dependencies = new TestDependencies()
      implicit val dao = dependencies.daoFactory.buildSundialDao()
      implicit val sundial = Bootstrap.bootstrapSundial(dependencies)
      implicit val processDef = testProcessDef(disabled = true)
      val taskDef = testTaskDef(shellScript(500, true))

      sundial.doWork() // should do nothing
      sundial.metrics.values mustBe(MetricValues(1, 0, 0, 0, 0, 0))
      sundial.doWork() // should still do nothing
      sundial.metrics.values mustBe(MetricValues(2, 0, 0, 0, 0, 0))

      processHistory() mustBe(Seq.empty)
      taskHistory(taskDef) mustBe(Seq.empty)
    }

    "run from a manual process trigger" in {
      implicit val dependencies = new TestDependencies()
      implicit val dao = dependencies.daoFactory.buildSundialDao()
      implicit val sundial = Bootstrap.bootstrapSundial(dependencies)
      implicit val processDef = testProcessDef(disabled = true) // to prevent the schedule from taking it
      val taskDef = testTaskDef(shellScript(500, true))

      val trigger = ProcessTriggerRequest(UUID.randomUUID(), processDef.name, new Date(), None, None)
      dao.triggerDao.saveProcessTriggerRequest(trigger)

      sundial.doWork() // this should start the task
      sundial.metrics.values mustBe(MetricValues(1, 1, 1, 1, 0, 0))
      sundial.doWork() // should do nothing
      sundial.metrics.values mustBe(MetricValues(2, 2, 1, 1, 0, 0))

      processHistory() mustBe(Seq(None))
      taskHistory(taskDef) mustBe(Seq(None))
      Thread.sleep(800)

      sundial.doWork() // should mark task 1 done and the process as done
      sundial.metrics.values mustBe(MetricValues(3, 3, 1, 1, 1, 0))
      sundial.doWork() // should do nothing, not even step
      sundial.metrics.values mustBe(MetricValues(4, 3, 1, 1, 1, 0))

      processHistory() mustBe(Seq(Some(true)))
      taskHistory(taskDef) mustBe(Seq(Some(true)))
    }

    "run a diamond hierarchy from a filtered process trigger" in {
      implicit val dependencies = new TestDependencies()
      implicit val dao = dependencies.daoFactory.buildSundialDao()
      implicit val sundial = Bootstrap.bootstrapSundial(dependencies)
      implicit val processDef = testProcessDef(disabled = true)
      val taskDef1 = testTaskDef(shellScript(500, true))
      val taskDef2 = testTaskDef(shellScript(500, true), requires = Seq(taskDef1))
      val taskDef3 = testTaskDef(shellScript(500, true), requires = Seq(taskDef1))
      val taskDef4 = testTaskDef(shellScript(500, true), requires = Seq(taskDef2, taskDef3))

      // The hierarchy normally       We will exclude 2, but the rest
      // looks like this:             should still run (including 4):
      //
      //      1                           1
      //    /   \                       /   \
      //   2     3                     X     3
      //    \   /                       \   /
      //      4                           4
      //
      val trigger = ProcessTriggerRequest(UUID.randomUUID(),
        processDef.name,
        new Date(),
        Some(Seq(taskDef1, taskDef3, taskDef4).map(_.name)),
        None)
      dao.triggerDao.saveProcessTriggerRequest(trigger)

      sundial.doWork() // this should start task 1
      sundial.metrics.values mustBe(MetricValues(1, 1, 1, 1, 0, 0))
      sundial.doWork() // should do nothing
      sundial.metrics.values mustBe(MetricValues(2, 2, 1, 1, 0, 0))

      processHistory() mustBe(Seq(None))
      taskHistory(taskDef1) mustBe(Seq(None))
      taskHistory(taskDef2) mustBe(Seq())
      taskHistory(taskDef3) mustBe(Seq())
      taskHistory(taskDef4) mustBe(Seq())
      Thread.sleep(800)

      sundial.doWork() // this should mark task 1 done and start task 3
      sundial.metrics.values mustBe(MetricValues(3, 3, 1, 2, 0, 0))
      sundial.doWork() // should do nothing
      sundial.metrics.values mustBe(MetricValues(4, 4, 1, 2, 0, 0))

      processHistory() mustBe(Seq(None))
      taskHistory(taskDef1) mustBe(Seq(Some(true)))
      taskHistory(taskDef2) mustBe(Seq())
      taskHistory(taskDef3) mustBe(Seq(None))
      taskHistory(taskDef4) mustBe(Seq())
      Thread.sleep(800)

      sundial.doWork() // this should mark task 3 done and start task 4
      sundial.metrics.values mustBe(MetricValues(5, 5, 1, 3, 0, 0))
      sundial.doWork() // should do nothing
      sundial.metrics.values mustBe(MetricValues(6, 6, 1, 3, 0, 0))

      processHistory() mustBe(Seq(None))
      taskHistory(taskDef1) mustBe(Seq(Some(true)))
      taskHistory(taskDef2) mustBe(Seq())
      taskHistory(taskDef3) mustBe(Seq(Some(true)))
      taskHistory(taskDef4) mustBe(Seq(None))
      Thread.sleep(800)

      sundial.doWork() // this should mark task 4 done and mark the process succeeded
      sundial.metrics.values mustBe(MetricValues(7, 7, 1, 3, 1, 0))

      processHistory() mustBe(Seq(Some(true)))
      taskHistory(taskDef1) mustBe(Seq(Some(true)))
      taskHistory(taskDef2) mustBe(Seq())
      taskHistory(taskDef3) mustBe(Seq(Some(true)))
      taskHistory(taskDef4) mustBe(Seq(Some(true)))
    }

    "run from a manual task trigger" in {
      implicit val dependencies = new TestDependencies()
      implicit val dao = dependencies.daoFactory.buildSundialDao()
      implicit val sundial = Bootstrap.bootstrapSundial(dependencies)
      implicit val processDef = testProcessDef(disabled = true) // to prevent the schedule from taking it
      val taskDef1 = testTaskDef(shellScript(500, true))
      val taskDef2 = testTaskDef(shellScript(500, true), requires = Seq(taskDef1))

      val trigger = TaskTriggerRequest(UUID.randomUUID(), processDef.name, taskDef2.name, new Date(), None)
      dao.triggerDao.saveTaskTriggerRequest(trigger)

      sundial.doWork() // this should start task 2 - task 1 should not be required
      sundial.metrics.values mustBe(MetricValues(1, 1, 1, 1, 0, 0))
      sundial.doWork() // should do nothing
      sundial.metrics.values mustBe(MetricValues(2, 2, 1, 1, 0, 0))

      processHistory() mustBe(Seq(None))
      taskHistory(taskDef1) mustBe(Seq())
      taskHistory(taskDef2) mustBe(Seq(None))
      Thread.sleep(800)

      sundial.doWork() // should mark task 2 done and the process as done
      sundial.metrics.values mustBe(MetricValues(3, 3, 1, 1, 1, 0))
      sundial.doWork() // should do nothing, not even step
      sundial.metrics.values mustBe(MetricValues(4, 3, 1, 1, 1, 0))

      processHistory() mustBe(Seq(Some(true)))
      taskHistory(taskDef1) mustBe(Seq())
      taskHistory(taskDef2) mustBe(Seq(Some(true)))
    }
  }
}
