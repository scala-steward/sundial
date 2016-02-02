package model

import java.util.{Date, UUID}

case class TaskMetadataEntry(id: UUID, taskId: UUID, when: Date, key: String, value: String)

case class TaskMetadata(entries: Seq[TaskMetadataEntry]) {
  def latest(key: String): Option[String] = entries.filter(_.key == key).sortBy(_.when).reverse.headOption.map(_.value)
}
