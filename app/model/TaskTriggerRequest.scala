package model

import java.util.{Date, UUID}

case class TaskTriggerRequest(
  requestId: UUID,
  processDefinitionName: String,
  taskDefinitionName: String,
  requestedAt: Date,
  startedProcessId: Option[UUID]
)
