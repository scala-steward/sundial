package dao.postgres

import java.sql.Connection
import java.util.UUID

import dao.ProcessDefinitionDao
import dao.postgres.common.{
  TaskDefinitionTemplateTable,
  TaskDefinitionTable,
  ProcessDefinitionTable
}
import dao.postgres.marshalling.{
  TaskDefinitionTemplateMarshaller,
  TaskDefinitionMarshaller,
  ProcessDefinitionMarshaller
}
import model.{TaskDefinitionTemplate, TaskDefinition, ProcessDefinition}
import util.JdbcUtil._

class PostgresProcessDefinitionDao(implicit conn: Connection)
    extends ProcessDefinitionDao {

  override def saveProcessDefinition(definition: ProcessDefinition) = {
    import ProcessDefinitionTable._
    val didUpdate = {
      val sql =
        s"""
           |UPDATE $TABLE
           |SET $COL_DESCRIPTION = ?,
           |    $COL_SCHEDULE = ?::jsonb,
           |    $COL_OVERLAP_ACTION = ?::process_overlap_action,
           |    $COL_TEAMS = ?::jsonb,
           |    $COL_NOTIFICATIONS = ?::jsonb,
           |    $COL_DISABLED = ?,
           |    $COL_CREATED_AT = ?
           |WHERE $COL_NAME = ?
         """.stripMargin
      val stmt = conn.prepareStatement(sql)
      val cols = Seq(COL_DESCRIPTION,
                     COL_SCHEDULE,
                     COL_OVERLAP_ACTION,
                     COL_TEAMS,
                     COL_NOTIFICATIONS,
                     COL_DISABLED,
                     COL_CREATED_AT,
                     COL_NAME)
      ProcessDefinitionMarshaller.marshal(definition, stmt, cols)
      stmt.executeUpdate() > 0
    }
    if (!didUpdate) {
      val sql =
        s"""
           |INSERT INTO $TABLE
           |($COL_NAME, $COL_DESCRIPTION, $COL_SCHEDULE, $COL_OVERLAP_ACTION, $COL_TEAMS, $COL_NOTIFICATIONS, $COL_DISABLED, $COL_CREATED_AT)
           |VALUES
           |(?, ?, ?::jsonb, ?::process_overlap_action, ?::jsonb, ?::jsonb, ?, ?)
         """.stripMargin
      val stmt = conn.prepareStatement(sql)
      val cols = Seq(COL_NAME,
                     COL_DESCRIPTION,
                     COL_SCHEDULE,
                     COL_OVERLAP_ACTION,
                     COL_TEAMS,
                     COL_NOTIFICATIONS,
                     COL_DISABLED,
                     COL_CREATED_AT)
      ProcessDefinitionMarshaller.marshal(definition, stmt, cols)
      stmt.execute()
    }
    definition
  }

  override def loadProcessDefinition(processDefinitionName: String) = {
    import ProcessDefinitionTable._
    val sql = s"SELECT * FROM $TABLE WHERE $COL_NAME = ?"
    val stmt = conn.prepareStatement(sql)
    stmt.setString(1, processDefinitionName)
    stmt
      .executeQuery()
      .map(ProcessDefinitionMarshaller.unmarshal)
      .toList
      .headOption
  }

  override def loadProcessDefinitions(): Seq[ProcessDefinition] = {
    import ProcessDefinitionTable._
    val sql = s"SELECT * FROM $TABLE"
    val stmt = conn.prepareStatement(sql)
    stmt.executeQuery().map(ProcessDefinitionMarshaller.unmarshal).toList
  }

  override def deleteProcessDefinition(processDefinitionName: String) = {
    import ProcessDefinitionTable._
    val sql = s"DELETE FROM $TABLE WHERE $COL_NAME = ?"
    val stmt = conn.prepareStatement(sql)
    stmt.setString(1, processDefinitionName)
    stmt.executeUpdate() > 0
  }

  override def saveTaskDefinition(definition: TaskDefinition) = {
    import TaskDefinitionTable._
    val didUpdate = {
      val sql =
        s"""
           |UPDATE $TABLE
           |SET $COL_EXECUTABLE = ?::jsonb,
           |    $COL_MAX_ATTEMPTS = ?,
           |    $COL_MAX_EXECUTION_TIME = ?,
           |    $COL_BACKOFF_SECONDS = ?,
           |    $COL_BACKOFF_EXPONENT = ?,
           |    $COL_REQUIRED_DEPS = ?,
           |    $COL_OPTIONAL_DEPS = ?,
           |    $COL_REQUIRE_EXPLICIT_SUCCESS = ?
           |WHERE $COL_NAME = ?
           |  AND $COL_PROC_ID = ?
         """.stripMargin
      val stmt = conn.prepareStatement(sql)
      val cols = Seq(
        COL_EXECUTABLE,
        COL_MAX_ATTEMPTS,
        COL_MAX_EXECUTION_TIME,
        COL_BACKOFF_SECONDS,
        COL_BACKOFF_EXPONENT,
        COL_REQUIRED_DEPS,
        COL_OPTIONAL_DEPS,
        COL_REQUIRE_EXPLICIT_SUCCESS,
        COL_NAME,
        COL_PROC_ID
      )
      TaskDefinitionMarshaller.marshal(definition, stmt, cols)
      stmt.executeUpdate() > 0
    }
    if (!didUpdate) {
      val sql =
        s"""
           |INSERT INTO $TABLE
           |($COL_NAME, $COL_PROC_ID, $COL_EXECUTABLE, $COL_MAX_ATTEMPTS, $COL_MAX_EXECUTION_TIME,
           | $COL_BACKOFF_SECONDS, $COL_BACKOFF_EXPONENT, $COL_REQUIRED_DEPS, $COL_OPTIONAL_DEPS,
           | $COL_REQUIRE_EXPLICIT_SUCCESS)
           |VALUES
           |(?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
         """.stripMargin
      val stmt = conn.prepareStatement(sql)
      val cols = Seq(
        COL_NAME,
        COL_PROC_ID,
        COL_EXECUTABLE,
        COL_MAX_ATTEMPTS,
        COL_MAX_EXECUTION_TIME,
        COL_BACKOFF_SECONDS,
        COL_BACKOFF_EXPONENT,
        COL_REQUIRED_DEPS,
        COL_OPTIONAL_DEPS,
        COL_REQUIRE_EXPLICIT_SUCCESS
      )
      TaskDefinitionMarshaller.marshal(definition, stmt, cols)
      stmt.execute()
    }
    definition
  }

  override def loadTaskDefinitions(processId: UUID) = {
    import TaskDefinitionTable._
    val sql = s"SELECT * FROM $TABLE WHERE $COL_PROC_ID = ?"
    val stmt = conn.prepareStatement(sql)
    stmt.setObject(1, processId)
    stmt.executeQuery().map(TaskDefinitionMarshaller.unmarshal).toList
  }

  override def deleteTaskDefinitionTemplate(processDefinitionName: String,
                                            taskDefinitionName: String) = {
    import TaskDefinitionTemplateTable._
    val sql =
      s"DELETE FROM $TABLE WHERE $COL_NAME = ? AND $COL_PROC_DEF_NAME = ?"
    val stmt = conn.prepareStatement(sql)
    stmt.setString(1, taskDefinitionName)
    stmt.setString(2, processDefinitionName)
    stmt.executeUpdate() > 0
  }

  override def deleteAllTaskDefinitionTemplates(
      processDefinitionName: String) = {
    import TaskDefinitionTemplateTable._
    val sql = s"DELETE FROM $TABLE WHERE $COL_PROC_DEF_NAME = ?"
    val stmt = conn.prepareStatement(sql)
    stmt.setString(1, processDefinitionName)
    stmt.executeUpdate()
  }

  override def deleteAllTaskDefinitions(processId: UUID) = {
    import TaskDefinitionTable._
    val sql = s"DELETE FROM $TABLE WHERE $COL_PROC_ID = ?"
    val stmt = conn.prepareStatement(sql)
    stmt.setObject(1, processId)
    stmt.executeUpdate()
  }

  override def saveTaskDefinitionTemplate(
      definition: TaskDefinitionTemplate): TaskDefinitionTemplate = {
    import TaskDefinitionTemplateTable._
    val didUpdate = {
      val sql =
        s"""
           |UPDATE $TABLE
           |SET $COL_EXECUTABLE = ?::jsonb,
           |    $COL_MAX_ATTEMPTS = ?,
           |    $COL_MAX_EXECUTION_TIME = ?,
           |    $COL_BACKOFF_SECONDS = ?,
           |    $COL_BACKOFF_EXPONENT = ?,
           |    $COL_REQUIRED_DEPS = ?,
           |    $COL_OPTIONAL_DEPS = ?,
           |    $COL_REQUIRE_EXPLICIT_SUCCESS = ?
           |WHERE $COL_NAME = ?
           |  AND $COL_PROC_DEF_NAME = ?
         """.stripMargin
      val stmt = conn.prepareStatement(sql)
      val cols = Seq(
        COL_EXECUTABLE,
        COL_MAX_ATTEMPTS,
        COL_MAX_EXECUTION_TIME,
        COL_BACKOFF_SECONDS,
        COL_BACKOFF_EXPONENT,
        COL_REQUIRED_DEPS,
        COL_OPTIONAL_DEPS,
        COL_REQUIRE_EXPLICIT_SUCCESS,
        COL_NAME,
        COL_PROC_DEF_NAME
      )
      TaskDefinitionTemplateMarshaller.marshal(definition, stmt, cols)
      stmt.executeUpdate() > 0
    }
    if (!didUpdate) {
      val sql =
        s"""
           |INSERT INTO $TABLE
           |($COL_NAME, $COL_PROC_DEF_NAME, $COL_EXECUTABLE, $COL_MAX_ATTEMPTS, $COL_MAX_EXECUTION_TIME,
           | $COL_BACKOFF_SECONDS, $COL_BACKOFF_EXPONENT, $COL_REQUIRED_DEPS, $COL_OPTIONAL_DEPS,
           | $COL_REQUIRE_EXPLICIT_SUCCESS)
           |VALUES
           |(?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
         """.stripMargin
      val stmt = conn.prepareStatement(sql)
      val cols = Seq(
        COL_NAME,
        COL_PROC_DEF_NAME,
        COL_EXECUTABLE,
        COL_MAX_ATTEMPTS,
        COL_MAX_EXECUTION_TIME,
        COL_BACKOFF_SECONDS,
        COL_BACKOFF_EXPONENT,
        COL_REQUIRED_DEPS,
        COL_OPTIONAL_DEPS,
        COL_REQUIRE_EXPLICIT_SUCCESS
      )
      TaskDefinitionTemplateMarshaller.marshal(definition, stmt, cols)
      stmt.execute()
    }
    definition
  }

  override def loadTaskDefinitionTemplates(
      processDefinitionName: String): Seq[TaskDefinitionTemplate] = {
    import TaskDefinitionTemplateTable._
    val sql = s"SELECT * FROM $TABLE WHERE $COL_PROC_DEF_NAME = ?"
    val stmt = conn.prepareStatement(sql)
    stmt.setString(1, processDefinitionName)
    stmt.executeQuery().map(TaskDefinitionTemplateMarshaller.unmarshal).toList
  }
}
