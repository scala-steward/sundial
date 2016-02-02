package common

import dao.memory.InMemorySundialDaoFactory
import service.{Dependencies, GlobalLock}
import service.notifications.{NoOpNotifications, Notifications}

class TestDependencies extends Dependencies {

  override lazy val daoFactory = new InMemorySundialDaoFactory()

  override val globalLock: GlobalLock = new GlobalLock {
    val lock = new Object()
    override def executeGuarded[T]()(f: => T): T = lock.synchronized(f)
  }

  override lazy val notifications: Notifications = NoOpNotifications

}
