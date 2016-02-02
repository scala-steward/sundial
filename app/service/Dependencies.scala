package service

import dao._
import service.notifications.Notifications

trait Dependencies {
  def globalLock: GlobalLock
  def daoFactory: SundialDaoFactory
  def notifications: Notifications
}
