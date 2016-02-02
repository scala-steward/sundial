package model

import java.util.Date

case class Team(name: String, email: String)

case class ProcessDefinition(name: String,
                             description: Option[String],
                             schedule: Option[ProcessSchedule],
                             overlapAction: ProcessOverlapAction,
                             teams: Seq[Team],
                             createdAt: Date,
                             isPaused: Boolean)
