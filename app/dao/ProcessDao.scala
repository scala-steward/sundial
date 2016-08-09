package dao

import java.util.{Date, UUID}
import model._

trait ProcessDao {
  def loadRunningProcesses(): Seq[Process]
  def loadProcess(id: UUID): Option[Process]
  def loadPreviousProcess(id: UUID, processDefinitionName: String): Option[Process]
  def findProcesses(processDefinitionName: Option[String] = None,
                    start: Option[Date] = None,
                    end: Option[Date] = None,
                    statuses: Option[Seq[ProcessStatusType]] = None,
                    limit: Option[Int] = None): Seq[Process]
  def loadMostRecentProcess(processDefinitionName: String): Option[Process]
  def loadTask(id: UUID): Option[Task]
  def loadTasksForProcess(processId: UUID): Seq[Task]
  def findTasks(processDefinitionName: Option[String],
                taskDefinitionName: Option[String],
                start: Option[Date],
                end: Option[Date],
                statuses: Option[Seq[TaskStatusType]],
                limit: Option[Int]): Seq[Task]
  def saveProcess(process: Process): Process
  def saveTask(task: Task): Task
  def findReportedTaskStatus(taskId: UUID): Option[ReportedTaskStatus]
  // will fail if already exists; TODO should this throw an exception instead?
  def saveReportedTaskStatus(reportedTaskStatus: ReportedTaskStatus): Boolean
}
