package model

import java.util.Date

trait ProcessSchedule {
  def nextRunAfter(previousRun: Date): Date
}
