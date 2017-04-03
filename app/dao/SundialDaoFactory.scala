package dao

import com.google.inject.ImplementedBy
import dao.postgres.PostgresSundialDaoFactory

@ImplementedBy(classOf[PostgresSundialDaoFactory])
trait SundialDaoFactory {
  def buildSundialDao(): SundialDao
  def withSundialDao[T](f: SundialDao => T) = {
    val dao = buildSundialDao()
    try {
      f(dao)
    } finally {
      dao.close()
    }
  }
}
