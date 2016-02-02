package model

import java.util.Date

case class ContinuousSchedule(bufferSeconds: Int) extends ProcessSchedule {
  override def nextRunAfter(previousRun: Date): Date = {
    new Date(previousRun.getTime + bufferSeconds * 1000)
  }
}
