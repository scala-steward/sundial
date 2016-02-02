package service

// only allows one of this process to run globally (including in other physical machines)
// implementation will coordinate with dynamodb, keyed by environment (+ hostname in dev)
// the lock updates its lease with a heartbeat in dynamodb as the argument executes
trait GlobalLock {
  def executeGuarded[T]()(f: => T): T
}
