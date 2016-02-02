package dao

import java.util.UUID

trait ExecutableStateDao[StateType] {
  def saveState(state: StateType)
  def loadState(taskId: UUID): Option[StateType]
}
