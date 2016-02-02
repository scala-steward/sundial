package dao

import java.util.UUID

import model.{TaskEventLog}

trait TaskLogsDao {
  def saveEvent(event: TaskEventLog) = saveEvents(Seq(event))
  def saveEvents(events: Seq[TaskEventLog])
  def loadEventsForTask(taskId: UUID): Seq[TaskEventLog]
}
