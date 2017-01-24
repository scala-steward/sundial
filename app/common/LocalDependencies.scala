package common

import dao.memory.InMemorySundialDaoFactory
import service.notifications.{DevelopmentEmailNotifications, Notification}
import service.{Dependencies, GlobalLock}

class LocalDependencies extends Dependencies {

  override lazy val daoFactory = new InMemorySundialDaoFactory()

  override lazy val notifications = Vector(new DevelopmentEmailNotifications(this))

  // only locks locally; doesn't use dynamodb
  override val globalLock: GlobalLock = new GlobalLock {
    val lock = new Object()

    override def executeGuarded[T]()(f: => T): T = lock.synchronized(f)
  }

}
