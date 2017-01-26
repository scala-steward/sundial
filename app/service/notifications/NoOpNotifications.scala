package service.notifications

import java.util.UUID

object NoOpNotifications extends Notification {
  override def notifyProcessFinished(processId: UUID): Unit = {}
}
