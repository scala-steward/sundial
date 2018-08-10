package service.notifications

import java.io.{BufferedWriter, File, FileWriter}

import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceAsync
import dao.SundialDaoFactory
import dto.DisplayModels
import model.{EmailNotification, ProcessStatus}

import scala.sys.process._

class DevelopmentEmailNotifications(daoFactory: SundialDaoFactory,
                                    displayModels: DisplayModels,
                                    sesClient: AmazonSimpleEmailServiceAsync)
    extends EmailNotifications(daoFactory,
                               "noreply@yourdomain.com",
                               displayModels,
                               sesClient) {

  override def sendEmail(processStatus: ProcessStatus,
                         previousProcessStatus: Option[ProcessStatus],
                         teams: Seq[EmailNotification],
                         subject: String,
                         body: String): Unit = {
    val outfile = File.createTempFile("sundial", ".html")
    val bw = new BufferedWriter(new FileWriter(outfile))
    bw.write(body)
    bw.close()

    Seq("open", outfile.getAbsolutePath()).!
  }

}
