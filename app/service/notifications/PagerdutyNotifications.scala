package service.notifications

import java.util.UUID

import dao.SundialDaoFactory
import model.{PagerdutyNotification, Process, ProcessStatusType}
import play.api.Logger
import play.api.libs.ws.WSClient
import speedwing.pagerduty.api.v0.Client
import speedwing.pagerduty.api.v0.models.{CreateEvent, EventType}

import scala.util.{Failure, Success}

class PagerdutyNotifications(wsClient: WSClient, daoFactory: SundialDaoFactory)
    extends Notification {

  private final val Log = Logger(classOf[PagerdutyNotifications])

  private final val PagerdutyPageMessage =
    "The Sundial Job %s, has failed at least %s time(s) in a row."

  override def notifyProcessFinished(processId: UUID): Unit =
    daoFactory.withSundialDao { implicit dao =>
      for {
        process <- dao.processDao.loadProcess(processId)
        processDef <- dao.processDefinitionDao.loadProcessDefinition(
          process.processDefinitionName)
      } yield {

        val pagerdutyNotifications = processDef.notifications.collect {
          case pagerduty: PagerdutyNotification => pagerduty
        }

        if (pagerdutyNotifications.nonEmpty) {
          val maxNumFailures =
            pagerdutyNotifications.map(_.numConsecutiveFailures).max
          val recentProcesses: Seq[Process] =
            dao.processDao.findProcesses(processDefinitionName =
                                           Some(process.processDefinitionName),
                                         limit = Some(maxNumFailures))
          val numConsecutiveFailedProcesses =
            getNumberConsecutiveFailures(recentProcesses)
          processPagerdutyNotifications(process,
                                        pagerdutyNotifications,
                                        numConsecutiveFailedProcesses)
        }
      }
    }

  private def processPagerdutyNotifications(
      process: Process,
      pagerdutyNotifications: Seq[PagerdutyNotification],
      numConsecutiveFailedProcesses: Int) = {
    import scala.concurrent.ExecutionContext.Implicits.global
    pagerdutyNotifications.foreach(pagerdutyNotification => {
      if (numConsecutiveFailedProcesses >= pagerdutyNotification.numConsecutiveFailures) {
        val createEvent = CreateEvent(
          pagerdutyNotification.serviceKey,
          EventType.Trigger,
          incidentKey = None,
          Some(
            PagerdutyPageMessage.format(
              process.processDefinitionName,
              pagerdutyNotification.numConsecutiveFailures.toString)),
          None,
          Some("Sundial"),
          None
        )
        val pagerdutyClient = new Client(wsClient, pagerdutyNotification.apiUrl)

        val pagerdutyRequest = pagerdutyClient.createEvents.post(createEvent)

        pagerdutyRequest.onComplete {
          case Success(pageId) =>
            Logger.info(
              s"Successfully submitted Pagerduty request with Id [${pageId.incidentKey}]")
          case Failure(e) =>
            Logger.error(s"Failed to submit Pagerduty request", e)
        }
      }
    })
  }

  private def getNumberConsecutiveFailures(recentProcesses: Seq[Process]): Int =
    recentProcesses
      .takeWhile(_.status.statusType == ProcessStatusType.Failed)
      .size

}
