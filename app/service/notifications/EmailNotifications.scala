package service.notifications

import java.util.UUID

import com.hbc.svc.sundial.v2.models.NotificationOptions
import dao._
import dto.{DisplayModels, ProcessDTO}
import model._
import play.api.Logging
import software.amazon.awssdk.services.ses.SesClient
import software.amazon.awssdk.services.ses.model._

import scala.collection.JavaConverters._

class EmailNotifications(daoFactory: SundialDaoFactory,
                         fromAddress: String,
                         displayModels: DisplayModels,
                         sesClient: SesClient)
    extends Notification
    with Logging {

  private def getSubject(processDTO: ProcessDTO): String = {
    val prefix = if (processDTO.success) {
      "Process succeeded: "
    } else {
      "Process failed: "
    }
    prefix + processDTO.name
  }

  override def notifyProcessFinished(processId: UUID): Unit =
    daoFactory.withSundialDao { implicit dao =>
      displayModels.fetchProcessDto(processId, generateGraph = true).foreach {
        dto =>
          val subject = getSubject(dto)
          val body = views.html.emails.process(dto).body
          for {
            process <- dao.processDao.loadProcess(processId)
            previousProcess = dao.processDao
              .loadPreviousProcess(processId, process.processDefinitionName)
            processDef <- dao.processDefinitionDao.loadProcessDefinition(
              process.processDefinitionName)
          } yield {
            val emailNotifications = processDef.notifications.collect {
              case emailNotification: EmailNotification => emailNotification
            }
            sendEmail(process.status,
                      previousProcess.map(_.status),
                      emailNotifications,
                      subject,
                      body)
          }
      }
    }

  protected def sendEmail(processStatus: ProcessStatus,
                          previousProcessStatus: Option[ProcessStatus],
                          teams: Seq[EmailNotification],
                          subject: String,
                          body: String): Unit = {
    val filteredTeams =
      filterNotificationTeams(teams, processStatus, previousProcessStatus)
    if (filteredTeams.nonEmpty) {
      val toAddresses =
        filteredTeams.map(team => s"${team.name} <${team.email}>")
      val sendEmailRequest = SendEmailRequest
        .builder()
        .destination(
          Destination.builder().toAddresses(toAddresses.asJava).build())
        .source(fromAddress)
        .message(
          Message
            .builder()
            .subject(Content.builder().data(subject).build())
            .body(
              Body.builder().html(Content.builder().data(body).build()).build())
            .build())
        .build()
      logger.info(s"Email request: $sendEmailRequest")
      sesClient.sendEmail(sendEmailRequest)
    }
  }

  private[notifications] def filterNotificationTeams(
      teams: Seq[EmailNotification],
      processStatus: ProcessStatus,
      previousProcessStatus: Option[ProcessStatus]): Seq[EmailNotification] = {
    if (previousProcessStatus.exists(_.isInstanceOf[ProcessStatus.Succeeded]) && processStatus
          .isInstanceOf[ProcessStatus.Succeeded]) {
      teams.filter(_.notifyAction == NotificationOptions.Always)
    } else if (previousProcessStatus.exists(
                 _.isInstanceOf[ProcessStatus.Failed]) && processStatus
                 .isInstanceOf[ProcessStatus.Failed]) {
      teams.filter(team =>
        team.notifyAction == NotificationOptions.Always || team.notifyAction == NotificationOptions.OnStateChangeAndFailures)
    } else {
      teams.filterNot(_.notifyAction == NotificationOptions.Never)
    }
  }
}
