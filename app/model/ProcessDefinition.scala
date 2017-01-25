package model

import java.util.Date

import com.fasterxml.jackson.annotation.JsonSubTypes.Type
import com.fasterxml.jackson.annotation.{JsonSubTypes, JsonTypeInfo}


@JsonTypeInfo(
  use = JsonTypeInfo.Id.NAME,
  include = JsonTypeInfo.As.PROPERTY,
  property = Constants.Type,
  visible = true
)
@JsonSubTypes(Array(
  new Type(value = classOf[EmailNotification], name = Constants.Email),
  new Type(value = classOf[PagerdutyNotification], name = Constants.Pagerduty)
))
sealed trait Notification {
  def `type`: String
}

case class EmailNotification(name: String, email: String, notifyAction: String) extends Notification {
  override val `type` = Constants.Email
}

case class PagerdutyNotification(serviceKey: String, sendResolved: Boolean, apiUrl: String, numConsecutiveFailures: Int) extends Notification {
  override val `type` = Constants.Pagerduty
}

case class Team(name: String, email: String, notifyAction: String)

case class ProcessDefinition(name: String,
                             description: Option[String],
                             schedule: Option[ProcessSchedule],
                             overlapAction: ProcessOverlapAction,
                             notifications: Seq[Notification],
                             createdAt: Date,
                             isPaused: Boolean)

object Constants {

  private[model] final val Type = "type"

  private[model] final val Email = "email"

  private[model] final val Pagerduty = "pagerduty"

}