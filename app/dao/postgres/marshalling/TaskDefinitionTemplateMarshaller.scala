package dao.postgres.marshalling

import java.sql.{Connection, PreparedStatement, ResultSet}

import dao.postgres.common.TaskDefinitionTemplateTable
import model._
import util.JdbcUtil._

object TaskDefinitionTemplateMarshaller {

  def marshal(definition: TaskDefinitionTemplate,
              stmt: PreparedStatement,
              columns: Seq[String],
              startIndex: Int = 1)(implicit conn: Connection) = {
    import TaskDefinitionTemplateTable._
    var index = startIndex
    columns.foreach { col =>
      col match {
        case COL_NAME => stmt.setString(index, definition.name)
        case COL_PROC_DEF_NAME =>
          stmt.setString(index, definition.processDefinitionName)
        case COL_EXECUTABLE =>
          stmt.setString(index,
                         PostgresJsonMarshaller.toJson(definition.executable))
        case COL_MAX_ATTEMPTS =>
          stmt.setInt(index, definition.limits.maxAttempts)
        case COL_MAX_EXECUTION_TIME =>
          stmt.setObject(index,
                         definition.limits.maxExecutionTimeSeconds.orNull)
        case COL_BACKOFF_SECONDS =>
          stmt.setInt(index, definition.backoff.seconds)
        case COL_BACKOFF_EXPONENT =>
          stmt.setDouble(index, definition.backoff.exponent)
        case COL_REQUIRED_DEPS =>
          stmt.setArray(index,
                        makeStringArray(definition.dependencies.required))
        case COL_OPTIONAL_DEPS =>
          stmt.setArray(index,
                        makeStringArray(definition.dependencies.optional))
        case COL_REQUIRE_EXPLICIT_SUCCESS =>
          stmt.setBoolean(index, definition.requireExplicitSuccess)
      }
      index += 1
    }
  }

  def unmarshal(rs: ResultSet): TaskDefinitionTemplate = {
    import TaskDefinitionTemplateTable._
    TaskDefinitionTemplate(
      name = rs.getString(COL_NAME),
      processDefinitionName = rs.getString(COL_PROC_DEF_NAME),
      executable =
        PostgresJsonMarshaller.toExecutable(rs.getString(COL_EXECUTABLE)),
      limits = TaskLimits(
        maxAttempts = rs.getInt(COL_MAX_ATTEMPTS),
        maxExecutionTimeSeconds = getIntOption(rs, COL_MAX_EXECUTION_TIME)
      ),
      backoff = TaskBackoff(
        seconds = rs.getInt(COL_BACKOFF_SECONDS),
        exponent = rs.getDouble(COL_BACKOFF_EXPONENT)
      ),
      dependencies = TaskDependencies(
        required = getStringArray(rs, COL_REQUIRED_DEPS).getOrElse(Seq.empty),
        optional = getStringArray(rs, COL_OPTIONAL_DEPS).getOrElse(Seq.empty)
      ),
      requireExplicitSuccess = rs.getBoolean(COL_REQUIRE_EXPLICIT_SUCCESS)
    )
  }

}
