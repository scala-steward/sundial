package dao.memory

import java.util.{Date, UUID}

import dao.ProcessDao
import model._

class InMemoryProcessDao extends ProcessDao {

  // everything in this class is synchronized
  private val lock = new Object()

  private val processes = new collection.mutable.HashMap[UUID, Process]()
  private val tasks = new collection.mutable.HashMap[UUID, Task]
  private val reportedTaskStatuses = new collection.mutable.HashMap[UUID, ReportedTaskStatus]

  def allProcesses() = lock.synchronized {
    processes.values.toList
  }

  override def loadRunningProcesses(): Seq[Process] = lock.synchronized {
    processes.values.filter { process =>
      val status = process.status
      status match {
        case ProcessStatus.Running() => true
        case _ => false
      }
    }.toList
  }

  override def loadTasksForProcess(processId: UUID): Seq[Task] = lock.synchronized {
    tasks.values.filter(_.processId == processId).toList
  }

  // will fail if already exists; TODO should this throw an exception instead?
  override def saveReportedTaskStatus(reportedTaskStatus: ReportedTaskStatus): Boolean = lock.synchronized {
    reportedTaskStatuses.get(reportedTaskStatus.taskId) match {
      case Some(_) => false
      case _ =>
        reportedTaskStatuses.put(reportedTaskStatus.taskId, reportedTaskStatus)
        true
    }
  }

  override def saveProcess(process: Process): Process = lock.synchronized {
    processes.put(process.id, process)
    process
  }

  override def saveTask(task: Task): Task = lock.synchronized {
    tasks.put(task.id, task)
    task
  }

  override def findReportedTaskStatus(taskId: UUID): Option[ReportedTaskStatus] = lock.synchronized {
    reportedTaskStatuses.get(taskId)
  }

  override def loadProcess(id: UUID): Option[Process] = lock.synchronized {
    processes.get(id)
  }

  override def loadPreviousProcess(id: UUID, processDefinitionName: String): Option[Process] = lock.synchronized {
    processes.values
      .filter(_.processDefinitionName == processDefinitionName)
      .filterNot(_.id == id)
      .toSeq
      .sortBy(_.startedAt)
      .reverse
      .headOption
  }


  override def loadMostRecentProcess(processDefinitionName: String): Option[Process] = lock.synchronized {
    processes.values
      .filter(_.processDefinitionName == processDefinitionName)
      .toSeq
      .sortBy(_.startedAt)
      .reverse
      .headOption
  }

  override def loadTask(id: UUID): Option[Task] = lock.synchronized {
    tasks.get(id)
  }

  override def findProcesses(processDefinitionName: Option[String],
                             start: Option[Date],
                             end: Option[Date],
                             statuses: Option[Seq[ProcessStatusType]],
                             limit: Option[Int]): Seq[Process] = lock.synchronized {
    val result = processes.values
      .filter { process =>
        processDefinitionName.map(_ == process.processDefinitionName).getOrElse(true) &&
          start.map(_.before(process.startedAt)).getOrElse(true) &&
          end.map(_.after(process.startedAt)).getOrElse(true) &&
          statuses.map(_ contains process.status.statusType).getOrElse(true)
      }
      .toSeq
      .sortBy(-_.startedAt.getTime)
    limit.map(result.take).getOrElse(result)
  }

  override def findTasks(processDefinitionName: Option[String],
                         taskDefinitionName: Option[String],
                         start: Option[Date],
                         end: Option[Date],
                         statuses: Option[Seq[TaskStatusType]],
                         limit: Option[Int]): Seq[Task] = lock.synchronized {
    val result = tasks.values
      .filter { task =>
        processDefinitionName.map(_ == task.processDefinitionName).getOrElse(true) &&
          start.map(_.before(task.startedAt)).getOrElse(true) &&
          end.map(_.after(task.startedAt)).getOrElse(true) &&
          statuses.map(_ contains task.status.statusType).getOrElse(true)
      }
      .toSeq
      .sortBy(-_.startedAt.getTime)
    limit.map(result.take).getOrElse(result)
  }

}
