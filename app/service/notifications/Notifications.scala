package service.notifications

import java.util.UUID

import model.{Task}

trait Notifications {
  def notifyProcessFinished(processId: UUID)
}
