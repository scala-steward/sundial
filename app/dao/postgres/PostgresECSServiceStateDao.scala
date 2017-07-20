package dao.postgres

import java.sql.Connection
import java.util.UUID

import dao.ExecutableStateDao
import dao.postgres.common.ECSStateTable
import dao.postgres.marshalling.PostgresECSExecutorStatus
import model.ECSContainerState
import util.JdbcUtil._

class PostgresECSServiceStateDao(implicit conn: Connection) extends ExecutableStateDao[ECSContainerState] {

  override def loadState(taskId: UUID) = {
    import ECSStateTable._
    val sql = s"SELECT * FROM $TABLE WHERE $COL_TASK_ID = ?"
    val stmt = conn.prepareStatement(sql)
    stmt.setObject(1, taskId)
    val rs = stmt.executeQuery()
    rs.map { row =>
      ECSContainerState(
        taskId = row.getObject(COL_TASK_ID).asInstanceOf[UUID],
        asOf = javaDate(row.getTimestamp(COL_AS_OF)),
        status = PostgresECSExecutorStatus(rs.getString(COL_STATUS)),
        ecsTaskArn = rs.getString(COL_TASK_ARN)
      )
    }.toList.headOption
  }

  override def saveState(state: ECSContainerState) = {
    import ECSStateTable._
    val didUpdate = {
      val sql =
        s"""
           |UPDATE $TABLE
           |SET
           |  $COL_STATUS = ?::task_executor_status,
           |  $COL_AS_OF = ?,
           |  $COL_TASK_ARN = ?
           |WHERE $COL_TASK_ID = ?
         """.stripMargin
      val stmt = conn.prepareStatement(sql)
      stmt.setString(1, PostgresECSExecutorStatus(state.status))
      stmt.setTimestamp(2, state.asOf)
      stmt.setString(3, state.ecsTaskArn)
      stmt.setObject(4, state.taskId)
      stmt.executeUpdate() > 0
    }
    if(!didUpdate) {
      val sql =
        s"""
           |INSERT INTO $TABLE
           |($COL_TASK_ID, $COL_AS_OF, $COL_STATUS, $COL_TASK_ARN)
           |VALUES
           |(?, ?, ?::task_executor_status, ?)
         """.stripMargin
      val stmt = conn.prepareStatement(sql)
      stmt.setObject(1, state.taskId)
      stmt.setTimestamp(2, state.asOf)
      stmt.setString(3, PostgresECSExecutorStatus(state.status))
      stmt.setString(4, state.ecsTaskArn)
      stmt.execute()
    }
  }

}
