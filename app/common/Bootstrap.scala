package common

import service._
import service.Sundial

object Bootstrap {

  def bootstrapSundial(dependencies: Dependencies): Sundial = {
    val containerServiceExecutor = new ContainerServiceExecutor()
    val shellCommandExecutor = new ShellCommandExecutor(dependencies.daoFactory)
    val taskExecutor = new TaskExecutor(containerServiceExecutor, shellCommandExecutor)
    val processStepper = new ProcessStepper(taskExecutor, dependencies.notifications)
    val sundial = new Sundial(dependencies.globalLock, processStepper, dependencies.daoFactory)

    sundial
  }

}
