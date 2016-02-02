package dao.postgres.common

import java.sql.{Connection, DriverManager}

class TestConnectionPool extends ConnectionPool {

  private val config = play.Play.application.configuration
  val dbUrl = config.getString("db.default.url")
  val user = config.getString("db.default.user")
  val password = config.getString("db.default.password")

  override def fetchConnection(): Connection = {
    DriverManager.getConnection(s"$dbUrl?user=$user&password=$password");
  }

}
