package dao.memory

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import dao.ExecutableStateDao
import model.ExecutableState

class InMemoryExecutableStateDao[T <: ExecutableState] extends ExecutableStateDao[T] {

  private val data = new ConcurrentHashMap[UUID, T]()

  override def saveState(state: T): Unit = data.put(state.taskId, state)

  override def loadState(taskId: UUID): Option[T] = Option(data.get(taskId))
}
