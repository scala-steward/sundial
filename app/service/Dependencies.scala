package service

import dao._
import service.notifications.Notification

trait Dependencies {

  def globalLock: GlobalLock

  def daoFactory: SundialDaoFactory

  def notifications: Seq[Notification]

}
