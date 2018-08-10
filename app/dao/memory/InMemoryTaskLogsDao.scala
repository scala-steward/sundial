package dao.memory

import java.util.{Date, UUID}

import dao.TaskLogsDao
import model.{TaskEventLog}

class InMemoryTaskLogsDao extends TaskLogsDao {

  private val lock = new Object()

  private val events = collection.mutable.HashMap[UUID, Seq[TaskEventLog]]()

  override def saveEvents(eventsToAdd: Seq[TaskEventLog]): Unit =
    lock.synchronized {
      eventsToAdd.foreach { event =>
        val bucket = events.get(event.taskId).getOrElse(Seq.empty)
        events.put(event.taskId, bucket :+ event)
      }
    }

  override def loadEventsForTask(taskId: UUID): Seq[TaskEventLog] =
    lock.synchronized {
      events.getOrElse(taskId, Seq.empty)
    }
}
