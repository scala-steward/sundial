package dao.memory

import dao._
import model.{ShellCommandState, ContainerServiceState}

class InMemorySundialDao extends SundialDao {

  override def ensureCommitted(): Unit = {}

  override def close(): Unit = {}

  override val taskLogsDao = new InMemoryTaskLogsDao()

  override val triggerDao = new InMemoryTriggerDao()

  override val taskMetadataDao = new InMemoryTaskMetadataDao()

  override val processDefinitionDao = new InMemoryProcessDefinitionDao()

  override val processDao = new InMemoryProcessDao()

  override val shellCommandStateDao = new InMemoryExecutableStateDao[ShellCommandState]()

  override val containerServiceStateDao = new InMemoryExecutableStateDao[ContainerServiceState]()

}
