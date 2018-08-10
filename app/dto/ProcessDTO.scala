package dto

import java.util.{Date, UUID}

case class ProcessDTO(id: UUID,
                      name: String,
                      status: String,
                      success: Boolean,
                      tasks: Seq[TaskDTO],
                      startedAt: Date,
                      endedAt: Option[Date],
                      durationStr: String,
                      graphUrl: Option[String])
