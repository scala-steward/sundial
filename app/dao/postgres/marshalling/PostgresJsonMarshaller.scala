package dao.postgres.marshalling

import java.io.ByteArrayOutputStream

import com.fasterxml.jackson.core.JsonEncoding
import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import com.fasterxml.jackson.databind.{DeserializationFeature, JsonNode, PropertyNamingStrategy}
import com.gilt.svc.sundial.v0.models.NotificationOptions
import model._
import util.Json

import scala.collection.JavaConversions._

class PostgresJsonMarshaller {

  private def mapper = {
    val mapper = Json.mapper()
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    mapper.setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)
    mapper
  }

  def toTeams(json: String): Seq[Team] = {
    mapper.readValue(json, classOf[Array[Team]])
  }

  def toJson[T](values: Seq[T]): String = {
    mapper.writeValueAsString(values)
  }

  def toNotifications(json: String): Seq[Notification] = {
    mapper.readValue(json, classOf[Array[Notification]])
  }

}

object PostgresJsonMarshaller {

  // Jackson has built-in support for doing polymorphic serialization.
  // However, we have so few types, it's simpler just to do it the obvious way.

  final val EXECUTABLE_TYPE_KEY = "type"
  final val EXECUTABLE_SHELL = "shell"
  final val EXECUTABLE_DOCKER = "docker"
  final val EXECUTABLE_BATCH = "batch"
  final val EXECUTABLE_EMR = "emr"

  final val SCHEDULE_TYPE_KEY = "type"
  final val SCHEDULE_CONTINUOUS = "continuous"
  final val SCHEDULE_CRON = "cron"

  final val TEAM_NAME = "name"
  final val TEAM_EMAIL = "email"
  final val TEAM_NOTIFY = "notify_options"

  private def mapper = {
    val mapper = Json.mapper()
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    mapper
  }

  private def toJson(tree: JsonNode): String = {
    val os = new ByteArrayOutputStream()
    val gen = mapper.getFactory().createGenerator(os, JsonEncoding.UTF8)
    mapper.writeTree(gen, tree)
    os.toString("UTF-8")
  }

  def toExecutable(json: String): Executable = {
    val tree = mapper.readTree(json)
    tree.get(EXECUTABLE_TYPE_KEY).asText() match {
      case EXECUTABLE_SHELL => mapper.treeToValue[ShellCommandExecutable](tree)
      case EXECUTABLE_DOCKER => mapper.treeToValue[ECSExecutable](tree)
      case EXECUTABLE_BATCH => mapper.treeToValue[BatchExecutable](tree)
      case EXECUTABLE_EMR => mapper.treeToValue[EmrJobExecutable](tree)
    }
  }

  def toJson(executable: Executable): String = {
    val executableType = executable match {
      case _: ShellCommandExecutable => EXECUTABLE_SHELL
      case _: ECSExecutable => EXECUTABLE_DOCKER
      case _: BatchExecutable => EXECUTABLE_BATCH
      case _: EmrJobExecutable => EXECUTABLE_EMR
    }
    val tree: ObjectNode = mapper.valueToTree(executable)
    tree.put(EXECUTABLE_TYPE_KEY, executableType)
    toJson(tree)
  }

  def toSchedule(json: String): ProcessSchedule = {
    val tree = mapper.readTree(json)
    tree.get(SCHEDULE_TYPE_KEY).asText() match {
      case SCHEDULE_CONTINUOUS => mapper.treeToValue[ContinuousSchedule](tree)
      case SCHEDULE_CRON => mapper.treeToValue[CronSchedule](tree)
    }
  }

  def toJson(schedule: ProcessSchedule): String = {
    val scheduleType = schedule match {
      case s: ContinuousSchedule => SCHEDULE_CONTINUOUS
      case s: CronSchedule => SCHEDULE_CRON
    }
    val tree: ObjectNode = mapper.valueToTree(schedule)
    tree.put(SCHEDULE_TYPE_KEY, scheduleType)
    toJson(tree)
  }

  @deprecated(message = "deprecated in favour of Notifications", since = "0.0.10")
  def toTeams(json: String): Seq[Team] = {
    if (json == null || json.isEmpty) {
      Seq.empty
    } else {
      mapper.readTree(json) match {
        case n: ArrayNode =>
          n.map { node =>
            val notificationOptions = Option(node.get(TEAM_NOTIFY)).map(_.asText("")).flatMap(NotificationOptions.fromString).getOrElse(NotificationOptions.OnStateChangeAndFailures)
            Team(name = node.get(TEAM_NAME).asText(), email = node.get(TEAM_EMAIL).asText(), notifyAction = notificationOptions.toString)
          }.toList
        case _ => throw new IllegalStateException(s"Teams JSON is not an array: $json")
      }
    }
  }

  @deprecated(message = "deprecated in favour of Notifications", since = "0.0.10")
  def toJson(teams: Seq[Team]): String = {
    val tree = mapper.getNodeFactory.arrayNode()
    teams.foreach { team =>
      val teamNode = mapper.getNodeFactory.objectNode()
      teamNode.put(TEAM_NAME, team.name)
      teamNode.put(TEAM_EMAIL, team.email)
      teamNode.put(TEAM_NOTIFY, team.notifyAction.toString)
      tree.add(teamNode)
    }
    toJson(tree)
  }

}
