package dao.memory

import java.util.UUID

import dao.TaskMetadataDao
import model.TaskMetadataEntry

class InMemoryTaskMetadataDao extends TaskMetadataDao {

  private val lock = new Object()
  private val entries = collection.mutable.MutableList[TaskMetadataEntry]()

  override def loadMetadataForTask(taskId: UUID): Seq[TaskMetadataEntry] =
    lock.synchronized {
      entries.filter(_.taskId == taskId).toList
    }

  override def saveMetadataEntries(entries: Seq[TaskMetadataEntry]): Unit =
    lock.synchronized {
      this.entries ++= entries
    }
}
