package service.notifications

import java.util.UUID

import dao.SundialDaoFactory
import model.{PagerdutyNotification, Process, ProcessStatus}
import play.api.Logger
import speedwing.pagerduty.api.v0.Client
import speedwing.pagerduty.api.v0.models.{CreateEvent, EventType}

import scala.util.{Failure, Success}

class PagerdutyNotifications(daoFactory: SundialDaoFactory) extends Notification {

  private final val Log = Logger(classOf[PagerdutyNotifications])

  private final val PagerdutyPageMessage = "The Sundial Job %s, has failed at least %s times in a row."

  override def notifyProcessFinished(processId: UUID): Unit = daoFactory.withSundialDao { implicit dao =>

    for {
      process <- dao.processDao.loadProcess(processId)
      processDef <- dao.processDefinitionDao.loadProcessDefinition(process.processDefinitionName)
    } yield {

      val pagerdutyNotifications = processDef.notifications.collect {
        case pagerduty: PagerdutyNotification => pagerduty
      }

      if (pagerdutyNotifications.nonEmpty) {
        val maxNumFailures = pagerdutyNotifications.map(_.numConsecutiveFailures).max
        val recentProcesses: Seq[Process] = dao.processDao.findProcesses(processDefinitionName = Some(process.processDefinitionName), limit = Some(maxNumFailures))
        val numConsecutiveFailedProcesses = getNumberConsecutiveFailures(recentProcesses)

        processPagerdutyNotifications(process, pagerdutyNotifications, numConsecutiveFailedProcesses)

      }
    }
  }

  private def processPagerdutyNotifications(process: Process, pagerdutyNotifications: Seq[PagerdutyNotification], numConsecutiveFailedProcesses: Int) = {
    pagerdutyNotifications.foreach(pagerdutyNotification => {
      if (numConsecutiveFailedProcesses >= pagerdutyNotification.numConsecutiveFailures) {
        val createEvent = CreateEvent(
          pagerdutyNotification.serviceKey,
          EventType.Trigger,
          incidentKey = None,
          Some(PagerdutyPageMessage.format(process.processDefinitionName, pagerdutyNotification.numConsecutiveFailures.toString)),
          None,
          Some("Sundial"),
          None)
        val pagerdutyClient = new Client(pagerdutyNotification.apiUrl)
        val pagerdutyRequest = pagerdutyClient.createEvents.post(createEvent)
        pagerdutyRequest.onComplete {
          case Success(pageId) => Log.info(s"Successfully submitted Pagerduty request with Id [$pageId]")
          case Failure(e) => Log.error(s"Failed to submit Pagerduty request", e)
        }
      }
    })
  }

  private def getNumberConsecutiveFailures(recentProcesses: Seq[Process]): Int = {
    if (recentProcesses.nonEmpty) {
      recentProcesses.takeWhile(_.status == ProcessStatus.Failed).size
    } else {
      0
    }
  }

}
