package dao

import java.util.UUID

import model.TaskMetadataEntry

trait TaskMetadataDao {
  def loadMetadataForTask(taskId: UUID): Seq[TaskMetadataEntry]
  def saveMetadataEntries(entries: Seq[TaskMetadataEntry])
}
