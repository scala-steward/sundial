package common

import java.sql.Connection

import dao.postgres.common.ConnectionPool
import play.api.db.DB

import play.api.Play.current

class JdbcConnectionPool() extends ConnectionPool {

  override def fetchConnection(): Connection = {
    DB.getConnection()
  }

}
