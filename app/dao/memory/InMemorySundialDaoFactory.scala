package dao.memory

import dao.SundialDaoFactory

class InMemorySundialDaoFactory extends SundialDaoFactory {

  private val dao = new InMemorySundialDao()

  override def buildSundialDao(): InMemorySundialDao = dao

}
