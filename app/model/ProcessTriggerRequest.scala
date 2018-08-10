package model

import java.util.{Date, UUID}

case class ProcessTriggerRequest(
    requestId: UUID,
    processDefinitionName: String,
    requestedAt: Date,
    taskFilter: Option[Seq[String]],
    startedProcessId: Option[UUID]
)
