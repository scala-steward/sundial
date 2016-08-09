package model

import java.util.Date

import com.gilt.svc.sundial.v0.models.NotificationOptions

case class Team(name: String, email: String, notifyAction: NotificationOptions)

case class ProcessDefinition(name: String,
                             description: Option[String],
                             schedule: Option[ProcessSchedule],
                             overlapAction: ProcessOverlapAction,
                             teams: Seq[Team],
                             createdAt: Date,
                             isPaused: Boolean)
