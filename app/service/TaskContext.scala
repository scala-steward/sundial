package service
import model.{ProcessDefinition, Task, TaskStatus, TaskDefinition}

trait TaskContext {

  def taskDefinitionForName(name: String): Option[TaskDefinition]

  def mostRecentStatusForTaskDefinition(taskDefinition: TaskDefinition): Option[TaskStatus]

  def tasksForTaskDefinition(taskDefinition: TaskDefinition): Seq[Task]

  def allConditions(taskDefinition: TaskDefinition): Map[String, TaskExecutionCondition]

  def taskExecutionCondition(taskDefinition: TaskDefinition): TaskExecutionCondition

  def taskNotCurrentlyRunningExecutionCondition(taskDefinition: TaskDefinition): TaskExecutionCondition

  def taskNotAlreadySuccessfulCondition(taskDefinition: TaskDefinition): TaskExecutionCondition

  def taskMaxAttemptsExecutionCondition(taskDefinition: TaskDefinition): TaskExecutionCondition

  def taskDependenciesExecutionCondition(taskDefinition: TaskDefinition): TaskExecutionCondition

  def taskBackoffExecutionCondition(taskDefinition: TaskDefinition): TaskExecutionCondition

  def processNotKilledExecutionCondition(): TaskExecutionCondition

}
