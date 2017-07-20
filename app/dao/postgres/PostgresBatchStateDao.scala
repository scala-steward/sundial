package dao.postgres

import java.sql.Connection
import java.util.UUID

import dao.ExecutableStateDao
import dao.postgres.marshalling.PostgresBatchExecutorStatus
import model.BatchContainerState
import util.JdbcUtil._

class PostgresBatchStateDao(implicit conn: Connection) extends ExecutableStateDao[BatchContainerState] {

  override def loadState(taskId: UUID) = {
    import dao.postgres.common.BatchStateTable._
    val sql = s"SELECT * FROM $TABLE WHERE $COL_TASK_ID = ?"
    val stmt = conn.prepareStatement(sql)
    stmt.setObject(1, taskId)
    val rs = stmt.executeQuery()
    rs.map { row =>
      BatchContainerState(
        taskId = row.getObject(COL_TASK_ID).asInstanceOf[UUID],
        asOf = javaDate(row.getTimestamp(COL_AS_OF)),
        status = PostgresBatchExecutorStatus(rs.getString(COL_STATUS)),
        jobName = rs.getString(COL_JOB_NAME),
        jobId = rs.getObject(COL_JOB_ID).asInstanceOf[UUID]
      )
    }.toList.headOption
  }

  override def saveState(state: BatchContainerState) = {
    import dao.postgres.common.BatchStateTable._
    val didUpdate = {
      val sql =
        s"""
           |UPDATE $TABLE
           |SET
           |  $COL_STATUS = ?::batch_executor_status,
           |  $COL_AS_OF = ?,
           |  $COL_JOB_ID = ?,
           |  $COL_JOB_NAME = ?
           |WHERE $COL_TASK_ID = ?
         """.stripMargin
      val stmt = conn.prepareStatement(sql)
      stmt.setString(1, PostgresBatchExecutorStatus(state.status))
      stmt.setTimestamp(2, state.asOf)
      stmt.setObject(3, state.jobId)
      stmt.setString(4, state.jobName)
      stmt.setObject(5, state.taskId)
      stmt.executeUpdate() > 0
    }
    if(!didUpdate) {
      val sql =
        s"""
           |INSERT INTO $TABLE
           |($COL_TASK_ID, $COL_AS_OF, $COL_STATUS, $COL_JOB_ID, $COL_JOB_NAME)
           |VALUES
           |(?, ?, ?::batch_executor_status, ?, ?)
         """.stripMargin
      val stmt = conn.prepareStatement(sql)
      stmt.setObject(1, state.taskId)
      stmt.setTimestamp(2, state.asOf)
      stmt.setString(3, PostgresBatchExecutorStatus(state.status))
      stmt.setObject(4, state.jobId)
      stmt.setString(5, state.jobName)
      stmt.execute()
    }
  }

}
