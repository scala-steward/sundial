package util

object CycleDetector {

  def hasCycle[T](current: T, next: T => Seq[T], seen: Set[T] = Set.empty[T]): Boolean = {
    if(seen contains current) {
      true
    } else {
      next(current).exists { nextItem =>
        hasCycle(nextItem, next, seen + current)
      }
    }
  }

}
