package dao

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
