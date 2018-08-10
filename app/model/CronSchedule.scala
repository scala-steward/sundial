package model

import java.util.Date

case class CronSchedule(minutes: String,
                        hours: String,
                        dayOfMonth: String,
                        month: String,
                        dayOfWeek: String)
    extends ProcessSchedule {

  require(nextRunAfter(new Date).after(new Date()))

  override def nextRunAfter(previousRun: Date): Date = {
    val expression =
      Seq("0", minutes, hours, dayOfMonth, month, dayOfWeek).mkString(" ")
    val quartzExpr = new org.quartz.CronExpression(expression)
    quartzExpr.getNextValidTimeAfter(previousRun)
  }
}
