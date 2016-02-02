package model

import java.util.UUID

// This class exists to get around a race condition around updating task status
// Rather than modifying the task object, the task reports by inserting a ReportedTaskStatus
// record, and then we dynamically resolve the actual task status when it is requested.
// The "status" field of the Task object is updated exclusively by the (globally single-threaded)
// process runner.
case class ReportedTaskStatus(taskId: UUID, status: CompletedTaskStatus)
