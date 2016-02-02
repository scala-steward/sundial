package dao

import model.{ShellCommandState, ContainerServiceState}

trait SundialDao {

  def processDao: ProcessDao
  def processDefinitionDao: ProcessDefinitionDao
  def taskLogsDao: TaskLogsDao
  def taskMetadataDao: TaskMetadataDao
  def triggerDao: TriggerDao
  def containerServiceStateDao: ExecutableStateDao[ContainerServiceState]
  def shellCommandStateDao: ExecutableStateDao[ShellCommandState]

  def ensureCommitted()
  def close()

}
