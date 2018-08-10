package dao.postgres.marshalling

import java.sql.{Connection, PreparedStatement, ResultSet}
import java.util.UUID
import dao.postgres.common.TaskTable
import model.{Task, TaskStatus}
import util.JdbcUtil._

object TaskMarshaller {

  def unmarshalTask(rs: ResultSet): Task = {
    import TaskTable._
    Task(
      id = rs.getObject(COL_ID).asInstanceOf[UUID],
      processId = rs.getObject(COL_PROCESS_ID).asInstanceOf[UUID],
      processDefinitionName = rs.getString(COL_PROC_DEF_NAME),
      taskDefinitionName = rs.getString(COL_TASK_DEF_NAME),
      executable =
        PostgresJsonMarshaller.toExecutable(rs.getString(COL_EXECUTABLE)),
      previousAttempts = rs.getInt(COL_ATTEMPTS),
      startedAt = javaDate(rs.getTimestamp(COL_STARTED)),
      status = rs.getString(COL_STATUS) match {
        case STATUS_SUCCEEDED =>
          TaskStatus.Success(javaDate(rs.getTimestamp(COL_ENDED_AT)))
        case STATUS_FAILED =>
          TaskStatus.Failure(javaDate(rs.getTimestamp(COL_ENDED_AT)),
                             Option(rs.getString(COL_REASON)))
        case STATUS_RUNNING => TaskStatus.Running()
      }
    )
  }

  def marshalTask(task: Task,
                  stmt: PreparedStatement,
                  columns: Seq[String],
                  startIndex: Int = 1)(implicit conn: Connection) = {
    import TaskTable._
    var index = startIndex
    columns.foreach { col =>
      col match {
        case COL_ID         => stmt.setObject(index, task.id)
        case COL_PROCESS_ID => stmt.setObject(index, task.processId)
        case COL_PROC_DEF_NAME =>
          stmt.setString(index, task.processDefinitionName)
        case COL_TASK_DEF_NAME => stmt.setString(index, task.taskDefinitionName)
        case COL_EXECUTABLE =>
          stmt.setString(index, PostgresJsonMarshaller.toJson(task.executable))
        case COL_ATTEMPTS => stmt.setInt(index, task.previousAttempts)
        case COL_STARTED  => stmt.setTimestamp(index, task.startedAt)
        case COL_STATUS =>
          stmt.setString(index, task.status match {
            case TaskStatus.Success(_)    => STATUS_SUCCEEDED
            case TaskStatus.Failure(_, _) => STATUS_FAILED
            case TaskStatus.Running()     => STATUS_RUNNING
          })
        case COL_REASON =>
          stmt.setString(index, task.status match {
            case TaskStatus.Failure(_, reasons) => reasons.mkString(",")
            case _                              => null
          })
        case COL_ENDED_AT =>
          stmt.setTimestamp(index, task.endedAt.getOrElse(null))
      }
      index += 1
    }
  }

}
