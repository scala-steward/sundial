package dao.postgres

import javax.inject.Inject

import dao.{SundialDao, SundialDaoFactory}
import play.api.db.DBApi

class PostgresSundialDaoFactory @Inject() (dbApi: DBApi) extends SundialDaoFactory {

  private val database = dbApi.database("default")

  override def buildSundialDao(): SundialDao = {
    implicit val connection = database.getConnection(false)
    new PostgresSundialDao
  }

}
