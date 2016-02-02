package dao.postgres

import java.sql.{Connection, ResultSet}
import java.util.{Date, UUID}

import dao.TaskLogsDao
import model.TaskEventLog

import util.JdbcUtil._

class PostgresTaskLogsDao(implicit conn: Connection) extends TaskLogsDao {

  final val TABLE = "task_log"
  final val COL_ID = "task_log_id"
  final val COL_TASK_ID = "task_id"
  final val COL_WHEN = "when_" // 'when' is a reserved word in PostgreSQL
  final val COL_SOURCE = "source"
  final val COL_MESSAGE = "message"

  private def unmarshal(rs: ResultSet): TaskEventLog = {
    TaskEventLog(
      id = rs.getObject(COL_ID).asInstanceOf[UUID],
      taskId = rs.getObject(COL_TASK_ID).asInstanceOf[UUID],
      when = new Date(rs.getTimestamp(COL_WHEN).getTime()),
      source = rs.getString(COL_SOURCE),
      message = rs.getString(COL_MESSAGE)
    )
  }

  override def loadEventsForTask(taskId: UUID) = {
    val stmt = conn.prepareStatement(s"SELECT * FROM $TABLE WHERE $COL_TASK_ID = ?")
    stmt.setObject(1, taskId)
    stmt.executeQuery().map(unmarshal).toList
  }

  override def saveEvents(events: Seq[TaskEventLog]) {
    val sql =
      s"""
         |INSERT INTO $TABLE
         |($COL_ID, $COL_TASK_ID, $COL_WHEN, $COL_SOURCE, $COL_MESSAGE)
         |VALUES
         |(?, ?, ?, ?, ?)
       """.stripMargin
    val stmt = conn.prepareStatement(sql)
    events.foreach { event =>
      stmt.setObject(1, event.id)
      stmt.setObject(2, event.taskId)
      stmt.setTimestamp(3, new java.sql.Timestamp(event.when.getTime))
      stmt.setString(4, event.source)
      stmt.setString(5, event.message)
      stmt.addBatch()
    }
    stmt.executeBatch()
  }

}
