package dao.postgres.marshalling

import java.sql.{Connection, PreparedStatement, ResultSet}
import java.util.UUID
import dao.postgres.common.TaskTriggerRequestTable
import model.TaskTriggerRequest
import util.JdbcUtil._

object TaskTriggerRequestMarshaller {

  def marshal(request: TaskTriggerRequest,
              stmt: PreparedStatement,
              columns: Seq[String],
              startIndex: Int = 1)(implicit conn: Connection) = {
    import TaskTriggerRequestTable._
    var index = startIndex
    columns.foreach { col =>
      col match {
        case COL_REQUEST_ID => stmt.setObject(index, request.requestId)
        case COL_PROCESS_DEF_NAME =>
          stmt.setString(index, request.processDefinitionName)
        case COL_TASK_DEF_NAME =>
          stmt.setString(index, request.taskDefinitionName)
        case COL_REQUESTED_AT => stmt.setTimestamp(index, request.requestedAt)
        case COL_STARTED_PROCESS_ID =>
          stmt.setObject(index, request.startedProcessId.orNull)
      }
      index += 1
    }
  }

  def unmarshal(rs: ResultSet): TaskTriggerRequest = {
    import TaskTriggerRequestTable._
    TaskTriggerRequest(
      requestId = rs.getObject(COL_REQUEST_ID).asInstanceOf[UUID],
      processDefinitionName = rs.getString(COL_PROCESS_DEF_NAME),
      taskDefinitionName = rs.getString(COL_TASK_DEF_NAME),
      requestedAt = javaDate(rs.getTimestamp(COL_REQUESTED_AT)),
      startedProcessId =
        Option(rs.getObject(COL_STARTED_PROCESS_ID)).map(_.asInstanceOf[UUID])
    )
  }

}
