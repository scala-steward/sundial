package dao.postgres

import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.util.concurrent.{Semaphore, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.{Date, UUID}
import javax.inject.{Inject, Singleton}

import dao.postgres.common.GlobalLockTable
import service.GlobalLock
import play.api.db.DBApi
import util.JdbcUtil._

case class GlobalLockMetricValues(executions: Int, failedExecutions: Int, acquires: Int, failedAcquires: Int, interrupts: Int, completions: Int)
class GlobalLockMetrics {
  val executions = new AtomicInteger()
  val failedExecutions = new AtomicInteger()
  val wakes = new AtomicInteger()
  val acquires = new AtomicInteger()
  val failedAcquires = new AtomicInteger()
  val interrupts = new AtomicInteger()
  val completions = new AtomicInteger()

  def values() = GlobalLockMetricValues(
    executions.get(),
    failedExecutions.get(),
    acquires.get(),
    failedAcquires.get(),
    interrupts.get(),
    completions.get()
  )
}
case class Lease(leaseId: UUID, start: Date, end: Date)

@Singleton
class PostgresGlobalLock @Inject() (dbApi: DBApi) extends GlobalLock {

  val metrics = new GlobalLockMetrics()

  val clientId = UUID.randomUUID()

  val database = dbApi.database("default")

  protected val LEASE_LENGTH_MS = 30000
  protected val RENEW_BUFFER_MS = 5000
  protected val CHECK_TIME_MS = 500

  override def executeGuarded[T]()(fn: => T) = {

    metrics.executions.incrementAndGet()

    // acquire an initial lease
    // in the background, if we get close to the end of the lease, take out a new one
    // if that fails, interrupt the process
    val result = new AtomicReference[T]()
    val start = new Date()
    val end = new Date(start.getTime() + LEASE_LENGTH_MS)
    val desiredLease = Lease(UUID.randomUUID(), start, end)
    if(!acquireLease(desiredLease)) {
      metrics.failedExecutions.incrementAndGet()
      metrics.failedAcquires.incrementAndGet()
    } else {
      metrics.acquires.incrementAndGet()

      val semaphore = new Semaphore(1)
      semaphore.acquire()
      val thread = new Thread {
        override def run(): Unit = {
          try {
            result.set(fn)
          } finally {
            semaphore.release()
          }
        }
      }
      thread.start()

      var currentLease: Lease = desiredLease
      var done: Boolean = false
      while(!done) {
        metrics.wakes.incrementAndGet()
        if(semaphore.tryAcquire(CHECK_TIME_MS, TimeUnit.MILLISECONDS)) {
          // thread completed normally
          done = true
          metrics.completions.incrementAndGet()
        } else if(!thread.isAlive) {
          // something appears to have happened that caused the thread to terminate
          done = true
        } else if(!thread.isInterrupted) {
          // if we're getting close to the end of the lease, renew the lease
          // if we can't renew the lease, interrupt the thread
          val isTooCloseToEnd = new Date().after(new Date(currentLease.end.getTime - RENEW_BUFFER_MS))
          if(isTooCloseToEnd) {
            val nextLease = Lease(UUID.randomUUID(), currentLease.end, new Date(currentLease.end.getTime() + LEASE_LENGTH_MS))
            if (acquireLease(nextLease)) {
              metrics.acquires.incrementAndGet()
              currentLease = nextLease
            } else {
              metrics.failedAcquires.incrementAndGet()
              thread.interrupt()
            }
          }
        }
      }
    }

    result.get()
  }

  private def acquireLease(lease: Lease): Boolean = {
    import GlobalLockTable._
    database.withConnection { conn =>
      conn.setAutoCommit(false)
      // we have an exclusion constraint, but we should still be
      // nice and check if there is a conflicting lease before
      // trying to insert
      val conflictingLease = {
        val sql =
          s"""
           |SELECT $COL_LEASE_ID, lower($COL_TIME_RANGE) AS lease_start, upper($COL_TIME_RANGE) AS lease_end
           |FROM $TABLE
           |WHERE (tsrange(?, ?) &&  $COL_TIME_RANGE)
         """.stripMargin
        val stmt = conn.prepareStatement(sql)
        stmt.setTimestamp(1, lease.start)
        stmt.setTimestamp(2, lease.end)
        val rs = stmt.executeQuery()
        rs.map { row =>
          Lease(
            row.getObject(COL_LEASE_ID).asInstanceOf[UUID],
            javaDate(row.getTimestamp("lease_start")),
            javaDate(row.getTimestamp("lease_end"))
          )
        }.toList.headOption
      }

      if(conflictingLease.isEmpty) {
        val sql =
          s"""
             |INSERT INTO $TABLE
             |($COL_LEASE_ID, $COL_TIME_RANGE, $COL_CLIENT_ID, $COL_CLIENT_HOSTNAME, $COL_CLIENT_IP_ADDR, $COL_CLIENT_PROCESS)
             |VALUES
             |(?, tsrange(?, ?, '[)'), ?, ?, ?::inet, ?)
           """.stripMargin
        val stmt = conn.prepareStatement(sql)
        val hostname = InetAddress.getLocalHost().getHostName()
        val ipAddr = InetAddress.getLocalHost().getHostAddress()
        val process = ManagementFactory.getRuntimeMXBean().getName()
        stmt.setObject(1, lease.leaseId)
        stmt.setTimestamp(2, lease.start)
        stmt.setTimestamp(3, lease.end)
        stmt.setObject(4, clientId)
        stmt.setString(5, hostname)
        stmt.setString(6, ipAddr)
        stmt.setString(7, process)
        stmt.execute()
        conn.commit()
        true
      } else {
        false
      }
    }
  }

}
