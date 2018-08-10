package dao.postgres

import java.sql.Connection
import java.util.UUID

import dao.TriggerDao
import dao.postgres.common.{
  KillProcessRequestTable,
  ProcessTriggerRequestTable,
  TaskTriggerRequestTable
}
import dao.postgres.marshalling.{
  TaskTriggerRequestMarshaller,
  ProcessTriggerRequestMarshaller
}
import model.{KillProcessRequest, TaskTriggerRequest, ProcessTriggerRequest}
import util.JdbcUtil._

class PostgresTriggerDao(implicit conn: Connection) extends TriggerDao {

  override def loadOpenProcessTriggerRequests(
      processDefinitionNameOpt: Option[String]) = {
    import ProcessTriggerRequestTable._
    val stmt = processDefinitionNameOpt match {
      case Some(processDefinitionName) =>
        val sql =
          s"""
             |SELECT * FROM $TABLE
             |WHERE $COL_PROCESS_DEF_NAME = ?
             |  AND $COL_STARTED_PROCESS_ID IS NULL
           """.stripMargin
        val stmt = conn.prepareStatement(sql)
        stmt.setObject(1, processDefinitionName)
        stmt
      case _ =>
        val sql =
          s"""
             |SELECT * FROM $TABLE
             |WHERE $COL_STARTED_PROCESS_ID IS NULL
           """.stripMargin
        conn.prepareStatement(sql)
    }
    val rs = stmt.executeQuery()
    rs.map(ProcessTriggerRequestMarshaller.unmarshal).toList
  }

  override def saveProcessTriggerRequest(request: ProcessTriggerRequest) = {
    import ProcessTriggerRequestTable._
    val didUpdate = {
      val sql =
        s"""
           |UPDATE $TABLE
           |SET
           |  $COL_PROCESS_DEF_NAME = ?,
           |  $COL_REQUESTED_AT = ?,
           |  $COL_STARTED_PROCESS_ID = ?,
           |  $COL_TASK_FILTER = ?
           |WHERE
           |  $COL_REQUEST_ID = ?
         """.stripMargin
      val stmt = conn.prepareStatement(sql)
      val cols = Seq(COL_PROCESS_DEF_NAME,
                     COL_REQUESTED_AT,
                     COL_STARTED_PROCESS_ID,
                     COL_TASK_FILTER,
                     COL_REQUEST_ID)
      ProcessTriggerRequestMarshaller.marshal(request, stmt, cols)
      stmt.executeUpdate() > 0
    }
    if (!didUpdate) {
      val sql =
        s"""
         |INSERT INTO $TABLE
         |($COL_REQUEST_ID, $COL_PROCESS_DEF_NAME, $COL_REQUESTED_AT, $COL_STARTED_PROCESS_ID, $COL_TASK_FILTER)
         |VALUES
         |(?, ?, ?, ?, ?)
       """.stripMargin
      val stmt = conn.prepareStatement(sql)
      val cols = Seq(COL_REQUEST_ID,
                     COL_PROCESS_DEF_NAME,
                     COL_REQUESTED_AT,
                     COL_STARTED_PROCESS_ID,
                     COL_TASK_FILTER)
      ProcessTriggerRequestMarshaller.marshal(request, stmt, cols)
      stmt.execute()
    }
    request
  }

  override def loadOpenTaskTriggerRequests(
      processDefinitionNameOpt: Option[String]) = {
    import TaskTriggerRequestTable._
    val stmt = processDefinitionNameOpt match {
      case Some(processDefinitionName) =>
        val sql =
          s"""
             |SELECT * FROM $TABLE
             |WHERE $COL_PROCESS_DEF_NAME = ?
             |  AND $COL_STARTED_PROCESS_ID IS NULL
           """.stripMargin
        val stmt = conn.prepareStatement(sql)
        stmt.setObject(1, processDefinitionName)
        stmt
      case _ =>
        val sql =
          s"""
             |SELECT * FROM $TABLE
             |WHERE $COL_STARTED_PROCESS_ID IS NULL
           """.stripMargin
        conn.prepareStatement(sql)
    }
    val rs = stmt.executeQuery()
    rs.map(TaskTriggerRequestMarshaller.unmarshal).toList
  }

  override def saveTaskTriggerRequest(request: TaskTriggerRequest) = {
    import TaskTriggerRequestTable._
    val didUpdate = {
      val sql =
        s"""
           |UPDATE $TABLE
           |SET
           |  $COL_PROCESS_DEF_NAME = ?,
           |  $COL_TASK_DEF_NAME = ?,
           |  $COL_REQUESTED_AT = ?,
           |  $COL_STARTED_PROCESS_ID = ?
           |WHERE
           |  $COL_REQUEST_ID = ?
         """.stripMargin
      val stmt = conn.prepareStatement(sql)
      val cols = Seq(COL_PROCESS_DEF_NAME,
                     COL_TASK_DEF_NAME,
                     COL_REQUESTED_AT,
                     COL_STARTED_PROCESS_ID,
                     COL_REQUEST_ID)
      TaskTriggerRequestMarshaller.marshal(request, stmt, cols)
      stmt.executeUpdate() > 0
    }
    if (!didUpdate) {
      val sql =
        s"""
         |INSERT INTO $TABLE
         |($COL_REQUEST_ID, $COL_PROCESS_DEF_NAME, $COL_TASK_DEF_NAME, $COL_REQUESTED_AT, $COL_STARTED_PROCESS_ID)
         |VALUES
         |(?, ?, ?, ?, ?)
       """.stripMargin
      val stmt = conn.prepareStatement(sql)
      val cols = Seq(COL_REQUEST_ID,
                     COL_PROCESS_DEF_NAME,
                     COL_TASK_DEF_NAME,
                     COL_REQUESTED_AT,
                     COL_STARTED_PROCESS_ID)
      TaskTriggerRequestMarshaller.marshal(request, stmt, cols)
      stmt.execute()
    }
    request
  }

  override def loadKillProcessRequests(
      processId: UUID): Seq[KillProcessRequest] = {
    import KillProcessRequestTable._
    val sql =
      s"""
         |SELECT *
         |FROM $TABLE
         |WHERE $COL_PROCESS_ID = ?
       """.stripMargin
    val stmt = conn.prepareStatement(sql)
    stmt.setObject(1, processId)
    val rs = stmt.executeQuery()
    rs.map { row =>
      KillProcessRequest(requestId = rs.getObject(1).asInstanceOf[UUID],
                         processId = rs.getObject(2).asInstanceOf[UUID],
                         when = javaDate(rs.getTimestamp(3)))
    }.toList
  }

  override def saveKillProcessRequest(
      request: KillProcessRequest): KillProcessRequest = {
    import KillProcessRequestTable._
    // Insert-only
    val sql =
      s"""
         |INSERT INTO $TABLE
         |($COL_REQUEST_ID, $COL_PROCESS_ID, $COL_REQUESTED_AT)
         |VALUES
         |(?, ?, ?)
       """.stripMargin
    val stmt = conn.prepareStatement(sql)
    stmt.setObject(1, request.requestId)
    stmt.setObject(2, request.processId)
    stmt.setTimestamp(3, request.when)
    stmt.execute()
    request
  }
}
