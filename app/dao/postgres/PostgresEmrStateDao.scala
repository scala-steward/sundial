package dao.postgres

import java.sql.Connection
import java.util.UUID

import dao.ExecutableStateDao
import dao.postgres.common.EmrStateTable
import dao.postgres.marshalling.PostgresEmrExecutorStatus
import model.EmrJobState
import util.JdbcUtil._

class PostgresEmrStateDao(implicit conn: Connection)
    extends ExecutableStateDao[EmrJobState] {

  override def loadState(taskId: UUID) = {
    import EmrStateTable._
    val sql = s"SELECT * FROM $TABLE WHERE $COL_TASK_ID = ?"
    val stmt = conn.prepareStatement(sql)
    stmt.setObject(1, taskId)
    val rs = stmt.executeQuery()
    rs.map { row =>
        EmrJobState(
          taskId = row.getObject(COL_TASK_ID).asInstanceOf[UUID],
          jobName = row.getString(COL_JOB_NAME),
          clusterId = row.getString(COL_CLUSTER_ID),
          stepIds = row.getString(COL_STEP_ID).split(","),
          region = row.getString(COL_REGION),
          asOf = javaDate(row.getTimestamp(COL_AS_OF)),
          status = PostgresEmrExecutorStatus(rs.getString(COL_STATUS))
        )
      }
      .toList
      .headOption
  }

  override def saveState(state: EmrJobState) = {
    import EmrStateTable._
    val didUpdate = {
      val sql =
        s"""
           |UPDATE $TABLE
           |SET
           |  $COL_STATUS = ?::emr_executor_status,
           |  $COL_AS_OF = ?
           |WHERE $COL_TASK_ID = ?
         """.stripMargin
      val stmt = conn.prepareStatement(sql)
      stmt.setString(1, PostgresEmrExecutorStatus(state.status))
      stmt.setTimestamp(2, state.asOf)
      stmt.setObject(3, state.taskId)
      stmt.executeUpdate() > 0
    }

    if (!didUpdate) {
      val sql =
        s"""
           |INSERT INTO $TABLE
           |($COL_TASK_ID, $COL_JOB_NAME, $COL_CLUSTER_ID, $COL_STEP_ID, $COL_REGION, $COL_AS_OF, $COL_STATUS)
           |VALUES
           |(?, ?, ?, ?, ?, ?, ?::emr_executor_status)
         """.stripMargin
      val stmt = conn.prepareStatement(sql)
      stmt.setObject(1, state.taskId)
      stmt.setString(2, state.jobName)
      stmt.setString(3, state.clusterId)
      stmt.setString(4, state.stepIds.mkString(","))
      stmt.setString(5, state.region)
      stmt.setTimestamp(6, state.asOf)
      stmt.setString(7, PostgresEmrExecutorStatus(state.status))
      stmt.execute()
    }
  }

}
