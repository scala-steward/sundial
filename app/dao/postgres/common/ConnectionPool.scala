package dao.postgres.common

import java.sql.Connection

trait ConnectionPool {
  def fetchConnection(): Connection
  def withConnection[T](f: Connection => T) = {
    val connection = fetchConnection()
    connection.setAutoCommit(false)
    try {
      f(connection)
    } finally {
      connection.close()
    }
  }
}
