package dao.postgres

import java.sql.Connection
import java.util.UUID

import dao.ExecutableStateDao
import dao.postgres.common.ShellCommandStateTable
import dao.postgres.marshalling.PostgresTaskExecutorStatus
import model.ShellCommandState
import util.JdbcUtil._

class PostgresShellCommandStateDao(implicit conn: Connection) extends ExecutableStateDao[ShellCommandState] {

  override def loadState(taskId: UUID) = {
    import ShellCommandStateTable._
    val sql = s"SELECT * FROM $TABLE WHERE $COL_TASK_ID = ?"
    val stmt = conn.prepareStatement(sql)
    stmt.setObject(1, taskId)
    val rs = stmt.executeQuery()
    rs.map { row =>
      ShellCommandState(
        taskId = row.getObject(COL_TASK_ID).asInstanceOf[UUID],
        asOf = javaDate(row.getTimestamp(COL_AS_OF)),
        status = PostgresTaskExecutorStatus(rs.getString(COL_STATUS))
      )
    }.toList.headOption
  }

  override def saveState(state: ShellCommandState) = {
    import ShellCommandStateTable._
    val didUpdate = {
      val sql =
        s"""
           |UPDATE $TABLE
           |SET $COL_STATUS = ?::task_executor_status,
           |    $COL_AS_OF = ?
           |WHERE $COL_TASK_ID = ?
         """.stripMargin
      val stmt = conn.prepareStatement(sql)
      stmt.setString(1, PostgresTaskExecutorStatus(state.status))
      stmt.setTimestamp(2, state.asOf)
      stmt.setObject(3, state.taskId)
      stmt.executeUpdate() >0
    }
    if(!didUpdate) {
      val sql =
        s"""
           |INSERT INTO $TABLE
           |($COL_TASK_ID, $COL_AS_OF, $COL_STATUS)
           |VALUES
           |(?, ?, ?::task_executor_status)
         """.stripMargin
      val stmt = conn.prepareStatement(sql)
      stmt.setObject(1, state.taskId)
      stmt.setTimestamp(2, state.asOf)
      stmt.setString(3, PostgresTaskExecutorStatus(state.status))
      stmt.execute()
    }
  }

}
