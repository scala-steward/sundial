package util

import java.util.Date

import org.joda.time.DateTime
import scala.language.implicitConversions

object Conversions {

  implicit def jodaDateTimeToDate(dateTime: DateTime): Date = dateTime.toDate()

  implicit def dateToJodaDateTime(date: Date): DateTime = new DateTime(date.getTime)

  implicit def jodaDateTimeToDateOption(dateTime: Option[DateTime]): Option[Date] = dateTime.map(jodaDateTimeToDate)

  implicit def dateToJodaDateTimeOption(date: Option[Date]): Option[DateTime] = date.map(dateToJodaDateTime)

}
