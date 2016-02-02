package util

import java.sql.{Connection, Timestamp, ResultSet}
import java.util.Date
import scala.language.implicitConversions

object JdbcUtil {

  implicit def resultSetItr(resultSet: ResultSet): Stream[ResultSet] = {
    new Iterator[ResultSet] {
      def hasNext = resultSet.next()
      def next() = resultSet
    }.toStream
  }

  implicit def javaDate(ts: Timestamp): Date = {
    new Date(ts.getTime())
  }

  implicit def dateToTimestamp(date: Date) = {
    if(date != null)
      new Timestamp(date.getTime())
    else
      null
  }

  private def getNullable[T](rs: ResultSet, f: ResultSet => T): Option[T] = {
    val obj = f(rs)
    if(rs.wasNull()) {
      Option.empty
    } else {
      Some(obj)
    }
  }

  def getIntOption(rs: ResultSet, col: String) = getNullable(rs, rs => rs.getInt(col))

  def makeStringArray(seq: Seq[String])(implicit conn: Connection) = {
    conn.createArrayOf("varchar", seq.toArray[AnyRef])
  }

  def getStringArray(rs: ResultSet, col: String) = {
    Option(rs.getArray(col)).map(_.getArray().asInstanceOf[Array[String]].toList)
  }

}