package dao.postgres.marshalling

import java.sql.{Connection, PreparedStatement, ResultSet}
import java.util.UUID

import dao.postgres.common.{ProcessTriggerRequestTable, TaskTriggerRequestTable}
import model.ProcessTriggerRequest
import util.JdbcUtil._

object ProcessTriggerRequestMarshaller {

  def marshal(request: ProcessTriggerRequest, stmt: PreparedStatement, columns: Seq[String], startIndex: Int = 1)
             (implicit conn: Connection) = {
    import ProcessTriggerRequestTable._
    var index = startIndex
    columns.foreach { col =>
      col match {
        case COL_REQUEST_ID => stmt.setObject(index, request.requestId)
        case COL_PROCESS_DEF_NAME => stmt.setString(index, request.processDefinitionName)
        case COL_REQUESTED_AT => stmt.setTimestamp(index, request.requestedAt)
        case COL_STARTED_PROCESS_ID => stmt.setObject(index, request.startedProcessId.orNull)
        case COL_TASK_FILTER => stmt.setArray(index, request.taskFilter.map(makeStringArray).orNull)
      }
      index += 1
    }
  }

  def unmarshal(rs: ResultSet): ProcessTriggerRequest = {
    import ProcessTriggerRequestTable._
    ProcessTriggerRequest(
      requestId = rs.getObject(COL_REQUEST_ID).asInstanceOf[UUID],
      processDefinitionName = rs.getString(COL_PROCESS_DEF_NAME),
      requestedAt = javaDate(rs.getTimestamp(COL_REQUESTED_AT)),
      startedProcessId = Option(rs.getObject(COL_STARTED_PROCESS_ID)).map(_.asInstanceOf[UUID]),
      taskFilter = getStringArray(rs, COL_TASK_FILTER)
    )
  }

}
