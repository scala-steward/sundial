package service.notifications

import java.util.UUID

object NoOpNotifications extends Notifications {
  override def notifyProcessFinished(processId: UUID): Unit = {}
}
