package dto

import java.util.{Date, UUID}

import model.{ProcessStatusType, ProcessDefinition, Process}

case class ProcessSummaryDTO(id: UUID, status: ProcessStatusType, startedAt: String, duration: Long)
case class ProcessDefinitionDTO(definition: ProcessDefinition, nextRun: Option[Date], lastRun: Option[Process], lastDuration: Option[String], runs: Seq[ProcessSummaryDTO])
