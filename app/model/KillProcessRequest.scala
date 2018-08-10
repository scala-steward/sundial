package model

import java.util.{Date, UUID}

case class KillProcessRequest(requestId: UUID, processId: UUID, when: Date)
