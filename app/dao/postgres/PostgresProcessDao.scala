package dao.postgres

import java.sql.Connection
import java.util.{Date, UUID}

import dao.ProcessDao
import dao.postgres.common.{ReportedTaskStatusTable, TaskTable, ProcessTable}
import dao.postgres.marshalling._
import model._
import org.postgresql.util.PSQLException

import util.JdbcUtil._

class PostgresProcessDao(implicit conn: Connection) extends ProcessDao {

  override def saveProcess(process: Process): Process = {
    import ProcessTable._
    val didUpdate = {
      val sql =
        s"""
           |UPDATE $TABLE
           |SET $COL_DEF_NAME = ?,
           |    $COL_STARTED = ?,
           |    $COL_STATUS = ?::process_status,
           |    $COL_ENDED_AT = ?,
           |    $COL_TASK_FILTER = ?
           |WHERE $COL_ID = ?
         """.stripMargin
      val stmt = conn.prepareStatement(sql)
      ProcessMarshaller.marshalProcess(process, stmt, Seq(COL_DEF_NAME, COL_STARTED, COL_STATUS, COL_ENDED_AT, COL_TASK_FILTER, COL_ID))
      stmt.executeUpdate() > 0
    }
    if(!didUpdate) {
      val sql =
        s"""
           |INSERT INTO $TABLE
           |($COL_ID, $COL_DEF_NAME, $COL_STARTED, $COL_STATUS, $COL_ENDED_AT, $COL_TASK_FILTER)
           |VALUES
           |(?, ?, ?, ?::process_status, ?, ?)
         """.stripMargin
      val stmt = conn.prepareStatement(sql)
      ProcessMarshaller.marshalProcess(process, stmt, Seq(COL_ID, COL_DEF_NAME, COL_STARTED, COL_STATUS, COL_ENDED_AT, COL_TASK_FILTER))
      stmt.execute()
    }
    process
  }

  override def loadProcess(id: UUID): Option[Process] = {
    import ProcessTable._
    val stmt = conn.prepareStatement(s"SELECT * FROM $TABLE WHERE $COL_ID = ?")
    stmt.setObject(1, id)
    val rs = stmt.executeQuery()
    rs.map(ProcessMarshaller.unmarshalProcess).headOption
  }

  override def loadPreviousProcess(processId: UUID, processDefinitionName: String): Option[Process] = {
    import ProcessTable._
    val stmt = conn.prepareStatement(s"SELECT * FROM $TABLE WHERE $COL_DEF_NAME = ? ORDER BY $COL_STARTED DESC LIMIT 2")
    stmt.setString(1, processDefinitionName)
    stmt.executeQuery().map(ProcessMarshaller.unmarshalProcess).toList.filterNot(_.id == processId).headOption
  }

  override def loadRunningProcesses(): Seq[Process] = {
    import ProcessTable._
    val stmt = conn.prepareStatement(s"SELECT * FROM $TABLE WHERE $COL_STATUS = ?::process_status")
    stmt.setString(1, STATUS_RUNNING)
    stmt.executeQuery().map(ProcessMarshaller.unmarshalProcess).toList
  }

  override def loadMostRecentProcess(processDefinitionName: String) = {
    import ProcessTable._
    val stmt = conn.prepareStatement(s"SELECT * FROM $TABLE WHERE $COL_DEF_NAME = ? ORDER BY $COL_STARTED DESC LIMIT 1")
    stmt.setString(1, processDefinitionName)
    stmt.executeQuery().map(ProcessMarshaller.unmarshalProcess).toList.headOption
  }

  /*
      TASKS
   */

  override def saveTask(task: Task): Task = {
    import TaskTable._
    val didUpdate = {
      val sql =
        s"""
           |UPDATE $TABLE
           |SET $COL_PROCESS_ID = ?,
           |    $COL_PROC_DEF_NAME = ?,
           |    $COL_TASK_DEF_NAME = ?,
           |    $COL_EXECUTABLE = ?::jsonb,
           |    $COL_ATTEMPTS = ?,
           |    $COL_STARTED = ?,
           |    $COL_STATUS = ?::task_status,
           |    $COL_REASON = ?,
           |    $COL_ENDED_AT = ?
           |WHERE $COL_ID = ?
         """.stripMargin
      val stmt = conn.prepareStatement(sql)
      val cols = Seq(COL_PROCESS_ID, COL_PROC_DEF_NAME, COL_TASK_DEF_NAME, COL_EXECUTABLE, COL_ATTEMPTS, COL_STARTED, COL_STATUS, COL_REASON, COL_ENDED_AT, COL_ID)
      TaskMarshaller.marshalTask(task, stmt, cols)
      stmt.executeUpdate() > 0
    }

    if(!didUpdate) {
      val sql =
        s"""
           |INSERT INTO $TABLE
           |($COL_ID, $COL_PROCESS_ID, $COL_PROC_DEF_NAME, $COL_TASK_DEF_NAME, $COL_EXECUTABLE, $COL_ATTEMPTS, $COL_STARTED, $COL_STATUS, $COL_REASON, $COL_ENDED_AT)
           |VALUES
           |(?, ?, ?, ?, ?::jsonb, ?, ?, ?::task_status, ?, ?)
         """.stripMargin
      val stmt = conn.prepareStatement(sql)
      val cols = Seq(COL_ID, COL_PROCESS_ID, COL_PROC_DEF_NAME, COL_TASK_DEF_NAME, COL_EXECUTABLE, COL_ATTEMPTS, COL_STARTED, COL_STATUS, COL_REASON, COL_ENDED_AT)
      TaskMarshaller.marshalTask(task, stmt, cols)
      stmt.execute()
    }

    task
  }

  override def loadTask(id: UUID): Option[Task] = {
    import TaskTable._
    val sql = s"SELECT * FROM $TABLE WHERE $COL_ID = ?"
    val stmt = conn.prepareStatement(sql)
    stmt.setObject(1, id)
    val rs = stmt.executeQuery()
    rs.map(TaskMarshaller.unmarshalTask).toList.headOption
  }

  override def loadTasksForProcess(processId: UUID): Seq[Task] = {
    import TaskTable._
    val sql = s"SELECT * FROM $TABLE WHERE $COL_PROCESS_ID = ?"
    val stmt = conn.prepareStatement(sql)
    stmt.setObject(1, processId)
    val rs = stmt.executeQuery()
    rs.map(TaskMarshaller.unmarshalTask).toList
  }

  override def findReportedTaskStatus(taskId: UUID): Option[ReportedTaskStatus] = {
    import ReportedTaskStatusTable._
    val sql = s"SELECT * FROM $TABLE WHERE $COL_TASK_ID = ?"
    val stmt = conn.prepareStatement(sql)
    stmt.setObject(1, taskId)
    val rs = stmt.executeQuery()
    rs.map { row =>
      val endedAt = javaDate(row.getTimestamp(COL_ENDED_AT))
      ReportedTaskStatus(
        taskId = row.getObject(COL_TASK_ID).asInstanceOf[UUID],
        status = row.getString(COL_STATUS) match {
          case STATUS_SUCCEEDED => TaskStatus.Success(endedAt)
          case STATUS_FAILED => TaskStatus.Failure(endedAt, Option(row.getString(COL_REASON)))
        }
      )
    }.toList.headOption
  }

  override def saveReportedTaskStatus(reportedTaskStatus: ReportedTaskStatus) = {
    import ReportedTaskStatusTable._
    findReportedTaskStatus(reportedTaskStatus.taskId) match {
      case Some(_) => false
      case _ =>
        val sql = s"INSERT INTO $TABLE($COL_TASK_ID, $COL_STATUS, $COL_REASON, $COL_ENDED_AT) VALUES (?, ?::task_status, ?, ?)"
        val stmt = conn.prepareStatement(sql)
        stmt.setObject(1, reportedTaskStatus.taskId)
        stmt.setString(2, reportedTaskStatus.status match {
          case s: TaskStatus.Success => STATUS_SUCCEEDED
          case s: TaskStatus.Failure => STATUS_FAILED
        })
        stmt.setString(3, reportedTaskStatus.status match {
          case TaskStatus.Failure(_, reasons) => reasons.mkString(",")
          case _ => null
        })
        stmt.setTimestamp(4, reportedTaskStatus.status.endedAt)
        stmt.execute()
        true
    }
  }

  def findProcesses(processDefinitionName: Option[String] = None,
                    start: Option[Date] = None,
                    end: Option[Date] = None,
                    statuses: Option[Seq[ProcessStatusType]] = None,
                    limit: Option[Int] = None): Seq[Process] = {
    import ProcessTable._
    val statusCodesOpt = statuses.map(_.map {
      case ProcessStatusType.Running => STATUS_RUNNING
      case ProcessStatusType.Succeeded => STATUS_SUCCEEDED
      case ProcessStatusType.Failed => STATUS_FAILED
    })
    val whereClauses = Seq(
      processDefinitionName.map(_ => s"$COL_DEF_NAME = ?"),
      start.map(_ => s"$COL_STARTED >= ?"),
      end.map(_ => s"$COL_STARTED <= ?"),
      statuses.map(_ => s"?::process_status[] @> ARRAY[$COL_STATUS]")
    ).flatMap(_.toSeq)
    val whereClause = {
      if(whereClauses.isEmpty) ""
      else "WHERE " + whereClauses.mkString("\n  AND ")
    }
    val limitClause = limit.map(_ => "LIMIT ?").getOrElse("")
    val sql =
      s"""
         |SELECT * FROM $TABLE
         |$whereClause
         |ORDER BY $COL_STARTED DESC
         |$limitClause
       """.stripMargin
    val stmt = conn.prepareStatement(sql)
    var index = 0
    def nextIndex() = {
      index += 1
      index
    }
    processDefinitionName.foreach(stmt.setString(nextIndex(), _))
    start.foreach(stmt.setTimestamp(nextIndex(), _))
    end.foreach(stmt.setTimestamp(nextIndex(), _))
    statusCodesOpt.foreach(statusCodes => stmt.setArray(nextIndex(), makeStringArray(statusCodes)))
    limit.foreach(stmt.setInt(nextIndex(), _))

    val rs = stmt.executeQuery()
    rs.map(ProcessMarshaller.unmarshalProcess).toList
  }

  override def findTasks(processDefinitionName: Option[String],
                         taskDefinitionName: Option[String],
                         start: Option[Date],
                         end: Option[Date],
                         statuses: Option[Seq[TaskStatusType]],
                         limit: Option[Int]): Seq[Task] = {
    import TaskTable._
    val statusCodesOpt = statuses.map(_.map {
      case TaskStatusType.Running => STATUS_RUNNING
      case TaskStatusType.Success => STATUS_SUCCEEDED
      case TaskStatusType.Failure => STATUS_FAILED
    })
    val whereClauses = Seq(
      processDefinitionName.map(_ => s"$COL_PROC_DEF_NAME = ?"),
      taskDefinitionName.map(_ => s"$COL_TASK_DEF_NAME = ?"),
      start.map(_ => s"$COL_STARTED >= ?"),
      end.map(_ => s"$COL_STARTED <= ?"),
      statuses.map(_ => s"$COL_STATUS IN UNNEST(?)")
    )
    val whereClause = {
      if(whereClauses.isEmpty) ""
      else whereClauses.flatten.mkString("\n  AND")
    }
    val limitClause = limit.map(_ => "LIMIT ?").getOrElse("")
    val sql =
      s"""
         |SELECT * FROM $TABLE
         |$whereClause
         |ORDER BY $COL_STARTED DESC
         |$limitClause
       """.stripMargin
    val stmt = conn.prepareStatement(sql)
    var index = 0
    def nextIndex() = {
      index += 1
      index
    }
    processDefinitionName.foreach(stmt.setString(nextIndex(), _))
    taskDefinitionName.foreach(stmt.setString(nextIndex(), _))
    start.foreach(stmt.setTimestamp(nextIndex(), _))
    end.foreach(stmt.setTimestamp(nextIndex(), _))
    statusCodesOpt.foreach(statusCodes => stmt.setArray(nextIndex(), makeStringArray(statusCodes)))
    limit.foreach(stmt.setInt(nextIndex(), _))

    val rs = stmt.executeQuery()
    rs.map(TaskMarshaller.unmarshalTask).toList
  }
}
