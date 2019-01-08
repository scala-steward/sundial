package dao.postgres.marshalling

import com.fasterxml.jackson.databind.PropertyNamingStrategy.SNAKE_CASE
import com.hbc.svc.sundial.v2.models.NotificationOptions
import model.{EmailNotification, PagerdutyNotification, Team}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatestplus.play.PlaySpec
import util.Json

@RunWith(classOf[JUnitRunner])
class PostgresJsonMarshallerTest extends PlaySpec {

  private val postgresJsonMarshaller = new PostgresJsonMarshaller()

  private val objectMapper = Json.mapper()
  objectMapper.setPropertyNamingStrategy(SNAKE_CASE)
  //  objectMapper.setVisibility(PropertyAccessor.FIELD,Visibility.ANY)

  "PostgresJsonMarshaller" should {

    "correctly deserialize a json string into Seq[Team]" in {
      val json =
        """
          | [{
          |   "name" : "teamName",
          |   "email" : "teamEmail",
          |   "notify_action": "on_state_change_and_failures"
          | }]
        """.stripMargin

      val expectedTeams: Seq[Team] =
        Vector(Team("teamName", "teamEmail", "on_state_change_and_failures"))
      val actualTeams = postgresJsonMarshaller.toTeams(json)
      actualTeams must be(expectedTeams)
    }

    "correctly serialise a Seq[Team] in a json string" in {
      val expectedJson =
        """
          | [{
          |   "name" : "teamName",
          |   "email" : "teamEmail",
          |   "notify_action": "on_state_change_and_failures"
          | }]
        """.stripMargin
      val expectedTeams: Seq[Team] =
        Vector(Team("teamName", "teamEmail", "on_state_change_and_failures"))
      val actualJson = postgresJsonMarshaller.toJson(expectedTeams)
      objectMapper.readTree(actualJson) must be(
        objectMapper.readTree(expectedJson))
    }

    "correctly deserialize a json string into Seq[Notification]" in {

      val json =
        """
          |[{"name":"name","email":"email","notify_action":"on_state_change_and_failures", "type": "email"},{"service_key":"service-key","api_url":"http://google.com", "type": "pagerduty","num_consecutive_failures":1}]
        """.stripMargin

      val notifications = Vector(
        EmailNotification(
          "name",
          "email",
          NotificationOptions.OnStateChangeAndFailures.toString),
        PagerdutyNotification("service-key", "http://google.com", 1)
      )

      val actualNotifications = postgresJsonMarshaller.toNotifications(json)

      actualNotifications must be(notifications)

    }

    "correctly serialise a Seq[Notification] in a json string" in {

      val json =
        """
          |[{"name":"name","email":"email","notify_action":"on_state_change_and_failures", "type": "email"},{"service_key":"service-key","api_url":"http://google.com", "type": "pagerduty","num_consecutive_failures":1}]
        """.stripMargin

      val notifications = Vector(
        EmailNotification(
          "name",
          "email",
          NotificationOptions.OnStateChangeAndFailures.toString),
        PagerdutyNotification("service-key", "http://google.com", 1)
      )

      println(s"bla1: ${postgresJsonMarshaller.toJson(notifications)}")
      println(s"bla2: ${objectMapper.writeValueAsString(notifications)}")

      objectMapper.readTree(json) must be(
        objectMapper.readTree(postgresJsonMarshaller.toJson(notifications)))

    }

  }

}
