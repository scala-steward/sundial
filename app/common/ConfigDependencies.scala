package common

import java.util.UUID

import dao.memory.InMemorySundialDaoFactory
import dao.postgres.{PostgresGlobalLock, PostgresSundialDaoFactory}
import service.notifications.{DevelopmentEmailNotifications, EmailNotifications, PagerdutyNotifications}
import service.{Dependencies, GlobalLock}

class ConfigDependencies extends Dependencies {

  lazy val config = play.Play.application.configuration

  lazy override val daoFactory = config.getString("dao.mode", "postgres") match {
    case "memory" => new InMemorySundialDaoFactory()
    case "postgres" => new PostgresSundialDaoFactory(new JdbcConnectionPool())
  }

  lazy override val globalLock = config.getString("globallock.mode", "postgres") match {
    case "memory" => new GlobalLock {
      val lock = new Object()
      override def executeGuarded[T]()(f: => T): T = lock.synchronized(f)
    }
    case "postgres" => new PostgresGlobalLock(new JdbcConnectionPool(), UUID.randomUUID())
  }

  lazy override val notifications = config.getString("notifications.mode", "email") match {
    case "browser" => Vector(new DevelopmentEmailNotifications(this))
    case "email" => Vector(new EmailNotifications(daoFactory, config.getString("notifications.from")))
    case "all" => Vector(
      new EmailNotifications(daoFactory, config.getString("notifications.from")),
      new PagerdutyNotifications(daoFactory)
    )
  }

}
