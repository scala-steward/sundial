package dao.postgres.marshalling

import java.sql.{Connection, PreparedStatement, ResultSet}

import dao.postgres.common.ProcessDefinitionTable
import model.{
  EmailNotification,
  Notification,
  ProcessDefinition,
  ProcessOverlapAction
}
import util.JdbcUtil._

object ProcessDefinitionMarshaller {

  private val postgresJsonMarshaller = new PostgresJsonMarshaller

  def marshal(definition: ProcessDefinition,
              stmt: PreparedStatement,
              columns: Seq[String],
              startIndex: Int = 1)(implicit conn: Connection) = {
    import ProcessDefinitionTable._
    var index = startIndex
    columns.foreach { col =>
      col match {
        case COL_NAME => stmt.setString(index, definition.name)
        case COL_DESCRIPTION =>
          stmt.setString(index, definition.description.orNull)
        case COL_SCHEDULE =>
          stmt.setString(
            index,
            definition.schedule.map(PostgresJsonMarshaller.toJson).orNull)
        case COL_OVERLAP_ACTION =>
          stmt.setString(index, definition.overlapAction match {
            case ProcessOverlapAction.Wait      => OVERLAP_WAIT
            case ProcessOverlapAction.Terminate => OVERLAP_TERMINATE
          })
        case COL_TEAMS => stmt.setString(index, "[]")
        case COL_NOTIFICATIONS =>
          stmt.setString(
            index,
            postgresJsonMarshaller.toJson(definition.notifications))
        case COL_DISABLED   => stmt.setBoolean(index, definition.isPaused)
        case COL_CREATED_AT => stmt.setTimestamp(index, definition.createdAt)
      }
      index += 1
    }
  }

  def unmarshal(rs: ResultSet): ProcessDefinition = {
    import ProcessDefinitionTable._
    ProcessDefinition(
      name = rs.getString(COL_NAME),
      description = Option(rs.getString(COL_DESCRIPTION)),
      schedule = Option(rs.getString(COL_SCHEDULE))
        .map(PostgresJsonMarshaller.toSchedule),
      overlapAction = rs.getString(COL_OVERLAP_ACTION) match {
        case OVERLAP_WAIT      => ProcessOverlapAction.Wait
        case OVERLAP_TERMINATE => ProcessOverlapAction.Terminate
      },
      notifications = this.getNotifications(rs),
      isPaused = rs.getBoolean(COL_DISABLED),
      createdAt = javaDate(rs.getTimestamp(COL_CREATED_AT))
    )
  }

  private def getNotifications(rs: ResultSet): Seq[Notification] = {
    import ProcessDefinitionTable._
    val teams = PostgresJsonMarshaller
      .toTeams(rs.getString(COL_TEAMS))
      .map(team => EmailNotification(team.name, team.email, team.notifyAction))
    val notifications =
      postgresJsonMarshaller.toNotifications(rs.getString(COL_NOTIFICATIONS))
    notifications ++ teams
  }

}
