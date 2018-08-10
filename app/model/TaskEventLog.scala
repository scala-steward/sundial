package model

import java.util.{UUID, Date}

case class TaskEventLog(id: UUID,
                        taskId: UUID,
                        when: Date,
                        source: String,
                        message: String)
