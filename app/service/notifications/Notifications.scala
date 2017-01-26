package service.notifications

import java.util.UUID

trait Notification {
  def notifyProcessFinished(processId: UUID)
}
