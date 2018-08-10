package dao

import model.{
  BatchContainerState,
  ECSContainerState,
  EmrJobState,
  ShellCommandState
}

trait SundialDao {

  def processDao: ProcessDao
  def processDefinitionDao: ProcessDefinitionDao
  def taskLogsDao: TaskLogsDao
  def taskMetadataDao: TaskMetadataDao
  def triggerDao: TriggerDao
  def ecsContainerStateDao: ExecutableStateDao[ECSContainerState]
  def batchContainerStateDao: ExecutableStateDao[BatchContainerState]
  def shellCommandStateDao: ExecutableStateDao[ShellCommandState]
  def emrJobStateDao: ExecutableStateDao[EmrJobState]

  def ensureCommitted()
  def close()

}
