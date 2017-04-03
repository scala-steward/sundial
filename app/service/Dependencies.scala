package service

import service.notifications.Notification

trait Dependencies {

  def globalLock: GlobalLock

  def notifications: Seq[Notification]

}
