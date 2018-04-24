package service

import java.io._
import java.util.concurrent.atomic.AtomicBoolean
import java.util.{Date, UUID}
import java.util.concurrent.Executors
import javax.inject.Inject

import dao._
import model._

import scala.collection.JavaConverters._

object ShellCommandRegistry {

  private var commands = Map[UUID, java.lang.Process]()
  private var checks = Map[UUID, () => Boolean]()

  // the "doneCheck" should indicate whether or not we're done processing
  // stdout and stderr for the process - otherwise we might erroneously
  // think that the process has failed, when in reality we just haven't
  // received its status message yet
  def putCommand(uuid: UUID, process: java.lang.Process, doneCheck: () => Boolean): Unit = {
    synchronized {
      commands = commands + (uuid -> process)
      checks = checks + (uuid -> doneCheck)
    }
  }

  def getCommand(uuid: UUID) = commands.get(uuid)

  def checkDone(uuid: UUID) = checks.get(uuid).map(_.apply()).getOrElse(true)

}

class ShellCommandExecutor @Inject() (daoFactory: SundialDaoFactory) extends SpecificTaskExecutor[ShellCommandExecutable, ShellCommandState] {

  private val threadPool = Executors.newCachedThreadPool()

  override def stateDao(implicit dao: SundialDao) = dao.shellCommandStateDao

  // Shell-based tasks do not use the HTTP API to pass status, events and metadata to the service.
  // Instead, we consume stdout and stderr.
  // The script can indicate success/failure by emitting "**status=success" or "**status=failure".
  // The script can set metadata using "**metadata:KEY=VALUE".
  // Any other log message will be interpreted as task event log.
  private def redirectOutput(stream: InputStream, kind: String, task: Task): AtomicBoolean = {
    val doneFlag = new AtomicBoolean(false)
    threadPool.submit(new Runnable {
      override def run(): Unit = daoFactory.withSundialDao { dao =>
        val br = new BufferedReader(new InputStreamReader(stream))
        var line: String = null
        do {
          line = br.readLine()
          if(line != null) {
            if(line.startsWith("**status=")) {
              val status = line.substring(line.indexOf("=") + 1) match {
                case "success" => TaskStatus.Success(new Date())
                case "failure" => TaskStatus.Failure(new Date(), Some("shell command reported failure"))
              }
              dao.processDao.saveReportedTaskStatus(new ReportedTaskStatus(task.id, status))
            } else if(line.startsWith("**metadata:")) {
              val parts = line.substring(line.indexOf(":") + 1)
              val key = parts.substring(0, parts.indexOf("="))
              val value = parts.substring(parts.indexOf("=") + 1)
              dao.taskMetadataDao.saveMetadataEntries(Seq(TaskMetadataEntry(UUID.randomUUID(), task.id, new Date(), key, value)))
            } else {
              val event = TaskEventLog(UUID.randomUUID(), task.id, new Date(), "shell", s"[$kind] $line")
              dao.taskLogsDao.saveEvents(Seq(event))
            }
          }
        } while(line != null)
        doneFlag.set(true)
      }
    })
    doneFlag
  }

  override protected def actuallyStartExecutable(executable: ShellCommandExecutable, task: Task)
                                                (implicit dao: SundialDao): ShellCommandState = {
    val scriptFile = File.createTempFile("sundial", ".sh")
    val bw = new BufferedWriter(new FileWriter(scriptFile))
    bw.write(executable.script)
    bw.close()

    val builder = new ProcessBuilder().command(Seq("bash", scriptFile.getAbsolutePath()).asJava)
    // communicate to the script how many attempts have been made before through an env variable
    // this is important for our unit tests and should not be removed!
    builder.environment().put("SUNDIAL_TASK_PREVIOUS_ATTEMPTS", task.previousAttempts.toString())
    builder.environment().putAll(executable.environmentVariables.asJava)
    val shellProcess = builder.start()
    val stdoutFlag = redirectOutput(shellProcess.getInputStream(), "stdout", task)
    val stderrFlag = redirectOutput(shellProcess.getInputStream(), "stderr", task)
    ShellCommandRegistry.putCommand(task.id, shellProcess, () => stdoutFlag.get() && stderrFlag.get())
    ShellCommandState(task.id, new Date(), ExecutorStatus.Running)
  }

  // we don't have Java 8 yet, so we have to implement this ourselves
  private def isAlive(proc: java.lang.Process) =
    try {
      proc.exitValue()
      false
    } catch {
      case e: IllegalThreadStateException => true
    }

  override protected def actuallyRefreshState(state: ShellCommandState)
                                             (implicit dao: SundialDao): ShellCommandState = {
    ShellCommandRegistry.getCommand(state.taskId) match {
      case Some(shellProcess) =>
        if(isAlive(shellProcess) || !ShellCommandRegistry.checkDone(state.taskId)) {
          state.copy(status = ExecutorStatus.Running)
        } else if(shellProcess.exitValue() == 0) {
          state.copy(status = ExecutorStatus.Succeeded)
        } else {
          state.copy(status = ExecutorStatus.Failed(Some(s"Shell command exit value ${shellProcess.exitValue()}")))
        }
      case _ =>
        // missing from registry – never started
        state.copy(status = ExecutorStatus.Failed(Some("Shell command never started")))
    }
  }

  override protected def actuallyKillExecutable(state: ShellCommandState, task: Task, reason: String)
                                               (implicit dao: SundialDao): Unit = {
    ShellCommandRegistry.getCommand(state.taskId).foreach { shellProcess =>
      shellProcess.destroy()
    }
  }

}
