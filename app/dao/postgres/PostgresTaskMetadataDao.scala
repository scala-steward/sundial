package dao.postgres

import java.sql.Connection
import java.util.UUID

import dao.TaskMetadataDao
import dao.postgres.common.TaskMetadataTable
import model.TaskMetadataEntry
import util.JdbcUtil._

class PostgresTaskMetadataDao(implicit conn: Connection) extends TaskMetadataDao {

  override def loadMetadataForTask(taskId: UUID) = {
    import TaskMetadataTable._
    val sql = s"SELECT * FROM $TABLE WHERE $COL_TASK_ID = ?"
    val stmt = conn.prepareStatement(sql)
    stmt.setObject(1, taskId)
    stmt.executeQuery().map { rs =>
      TaskMetadataEntry(
        id = rs.getObject(COL_ID).asInstanceOf[UUID],
        taskId = rs.getObject(COL_TASK_ID).asInstanceOf[UUID],
        when = javaDate(rs.getTimestamp(COL_WHEN)),
        key = rs.getString(COL_KEY),
        value = rs.getString(COL_VALUE)
      )
    }.toList
  }

  override def saveMetadataEntries(entries: Seq[TaskMetadataEntry]) = {
    import TaskMetadataTable._
    val sql =
      s"""
         |INSERT INTO $TABLE
         |($COL_ID, $COL_TASK_ID, $COL_WHEN, $COL_KEY, $COL_VALUE)
         |VALUES
         |(?, ?, ?, ?, ?)
       """.stripMargin
    val stmt = conn.prepareStatement(sql)
    entries.foreach { entry =>
      stmt.setObject(1, entry.id)
      stmt.setObject(2, entry.taskId)
      stmt.setTimestamp(3, entry.when)
      stmt.setString(4, entry.key)
      stmt.setString(5, entry.value)
      stmt.addBatch()
    }
    stmt.executeBatch()
  }

}
