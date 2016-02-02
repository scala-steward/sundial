package dao.postgres

import dao.postgres.common.ConnectionPool
import dao.{SundialDao, SundialDaoFactory}

class PostgresSundialDaoFactory(connectionPool: ConnectionPool) extends SundialDaoFactory {

  override def buildSundialDao(): SundialDao = {
    implicit val conn = connectionPool.fetchConnection()
    conn.setAutoCommit(false)
    new PostgresSundialDao()
  }

}
