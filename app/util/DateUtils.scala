package util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

object DateUtils {

  val basicDateTimeFormat = new SimpleDateFormat("MMM d, H:mm z")

  def prettyRelativeTime(when: Date, now: Date): String = {
    s"${prettyDuration(when, now)} ago"
  }

  def prettyDuration(start: Date, end: Date): String = {
    val diff = end.getTime - start.getTime
    prettyDuration(diff, TimeUnit.MILLISECONDS)
  }

  def prettyDuration(amount: Long, unit: TimeUnit): String = {
    if (unit.toSeconds(amount) < 120) {
      s"${unit.toSeconds(amount)} seconds"
    } else if (unit.toMinutes(amount) < 180) {
      s"${unit.toMinutes(amount)} minutes"
    } else if (unit.toHours(amount) < 72) {
      s"${unit.toHours(amount)} hours"
    } else {
      s"${unit.toDays(amount)} days"
    }
  }

}
