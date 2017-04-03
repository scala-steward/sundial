package model

import java.text.ParseException
import java.util.GregorianCalendar

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatestplus.play.PlaySpec

@RunWith(classOf[JUnitRunner])
class CronScheduleSpec extends PlaySpec {

  "Cron scheduler" should {

    "successfully parse cron entry for 10pm every day" in {
      val cronSchedule = CronSchedule("0", "22", "*", "*", "?")
      val date = new GregorianCalendar(2015, 10, 5, 21, 0).getTime
      val expectedNextDate = new GregorianCalendar(2015, 10, 5, 22, 0).getTime
      val nextDate = cronSchedule.nextRunAfter(date)
      nextDate must be(expectedNextDate)
    }

    "Throw exception on creation if cron schedlue is invalid" in {
      intercept[ParseException] {
        CronSchedule("0", "22", "*", "*", "*")
      }
    }
  }

}
