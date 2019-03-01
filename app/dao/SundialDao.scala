package dao

import model.{BatchContainerState, EmrJobState, ShellCommandState}

trait SundialDao {

  def processDao: ProcessDao
  def processDefinitionDao: ProcessDefinitionDao
  def taskLogsDao: TaskLogsDao
  def taskMetadataDao: TaskMetadataDao
  def triggerDao: TriggerDao
  def batchContainerStateDao: ExecutableStateDao[BatchContainerState]
  def shellCommandStateDao: ExecutableStateDao[ShellCommandState]
  def emrJobStateDao: ExecutableStateDao[EmrJobState]

  def ensureCommitted()
  def close()

}
