package service.notifications

import java.util.UUID

import com.amazonaws.regions.Regions
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceAsyncClient
import com.amazonaws.services.simpleemail.model._
import common.SundialGlobal
import dao._
import dto.{DisplayModels, ProcessDTO}
import model._
import play.api.Logger

import scala.collection.JavaConversions._


class EmailNotifications(daoFactory: SundialDaoFactory, fromAddress: String) extends Notifications {

  private def getSubject(processDTO: ProcessDTO): String = {
    val prefix = if (processDTO.success) {
      "Process succeeded: "
    } else {
      "Process failed: "
    }
    prefix + processDTO.name
  }

  override def notifyProcessFinished(processId: UUID): Unit = daoFactory.withSundialDao { implicit dao =>
    DisplayModels.fetchProcessDto(processId, generateGraph = true).foreach { dto =>
      val subject = getSubject(dto)
      val body = views.html.emails.process(dto).body
      for {
        process <- dao.processDao.loadProcess(processId)
        processDef <- dao.processDefinitionDao.loadProcessDefinition(process.processDefinitionName)
      } yield {
        sendEmail(processDef.teams, subject, body)
      }
    }
  }

  protected val sesClient: AmazonSimpleEmailServiceAsyncClient = new AmazonSimpleEmailServiceAsyncClient().withRegion(Regions.valueOf(SundialGlobal.awsRegion))

  protected def sendEmail(teams: Seq[Team], subject: String, body: String): Unit = {
    val toAddresses = teams.map(team => s"${team.name} <${team.email}>").toList
    val sendEmailRequest = new SendEmailRequest()
      .withDestination(new Destination().withToAddresses(toAddresses))
      .withSource(fromAddress)
      .withMessage(new Message()
                     .withSubject(new Content(subject))
                     .withBody(new Body().withHtml(new Content(body))))
    Logger.info(s"Email request: $sendEmailRequest")
    sesClient.sendEmail(sendEmailRequest)
  }
}
