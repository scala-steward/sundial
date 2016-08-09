package service.notifications

import java.io.{BufferedWriter, File, FileWriter}

import model.{ProcessStatus, Team}
import service.Dependencies

import sys.process._

class DevelopmentEmailNotifications(dependencies: Dependencies)
  extends EmailNotifications(dependencies.daoFactory, "noreply@yourdomain.com") {

  override protected def sendEmail(processStatus: ProcessStatus, previousProcessStatus: Option[ProcessStatus], teams: Seq[Team], subject: String, body: String): Unit = {
    val outfile = File.createTempFile("sundial", ".html")
    val bw = new BufferedWriter(new FileWriter(outfile))
    bw.write(body)
    bw.close()

    Seq("open", outfile.getAbsolutePath()).!
  }

}
