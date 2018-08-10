package model

import java.util.UUID

case class TaskBackoff(seconds: Int, exponent: Double = 1) {
  def backoffTimeInSeconds(previousAttempts: Int) = {
    (seconds * Math.pow(exponent, previousAttempts)).toLong
  }
}

case class TaskDependencies(required: Seq[String], optional: Seq[String])
case class TaskLimits(maxAttempts: Int, maxExecutionTimeSeconds: Option[Int])
case class TaskDefinition(name: String,
                          processId: UUID,
                          executable: Executable,
                          limits: TaskLimits,
                          backoff: TaskBackoff,
                          dependencies: TaskDependencies,
                          requireExplicitSuccess: Boolean)

case class TaskDefinitionTemplate(name: String,
                                  processDefinitionName: String,
                                  executable: Executable,
                                  limits: TaskLimits,
                                  backoff: TaskBackoff,
                                  dependencies: TaskDependencies,
                                  requireExplicitSuccess: Boolean)
