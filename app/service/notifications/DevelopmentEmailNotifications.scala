package service.notifications

import java.io.{FileWriter, BufferedWriter, File}

import common.LocalDependencies
import model.Team
import service.Dependencies
import sys.process._

class DevelopmentEmailNotifications(dependencies: Dependencies)
  extends EmailNotifications(dependencies.daoFactory, "noreply@yourdomain.com") {

  override protected def sendEmail(teams: Seq[Team], subject: String, body: String): Unit = {
    val outfile = File.createTempFile("sundial", ".html")
    val bw = new BufferedWriter(new FileWriter(outfile))
    bw.write(body)
    bw.close()

    Seq("open", outfile.getAbsolutePath()).!
  }

}
