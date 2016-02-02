package common

import java.util.Date

import model._
import service.Dependencies

object Samples {

  def buildStockPriceJob(dependencies: Dependencies): ProcessDefinition = dependencies.daoFactory.withSundialDao { dao =>
    val processDef = new ProcessDefinition(
      "stockPrice",
      Option.empty,
      Some(ContinuousSchedule(bufferSeconds = 2)),
      ProcessOverlapAction.Wait,
      Seq.empty,
      new Date(),
      true
    )

    val taskDef = new TaskDefinitionTemplate(
      "googlePrice",
      processDef.name,
      ShellCommandExecutable(
        """
          |QUOTE_DATA=$(curl 'http://download.finance.yahoo.com/d/quotes.csv?s=GOOG&f=pd1t1&e=.csv')
          |echo "**metadata:price=$(echo $QUOTE_DATA | cut -d, -f1)"
          |echo "**metadata:asof=$(echo $QUOTE_DATA | cut -d, -f2 | tr -d \") $(echo $QUOTE_DATA | cut -d, -f3 | tr -d \")"
          |echo "**status=success"
        """.stripMargin,
        Map.empty
      ),
      TaskLimits(1, None),
      TaskBackoff(5),
      TaskDependencies(Seq.empty, Seq.empty),
      true
    )

    dao.processDefinitionDao.saveProcessDefinition(processDef)
    dao.processDefinitionDao.saveTaskDefinitionTemplate(taskDef)

    processDef
  }



}
