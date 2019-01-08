package config

import javax.inject.Inject
import com.google.inject.{AbstractModule, Provides, Singleton}
import dao.SundialDaoFactory
import dto.DisplayModels
import org.lyranthe.prometheus.client.{DefaultRegistry, Registry, jmx}
import play.api.libs.ws.WSClient
import play.api.{Configuration, Environment, Logger}
import service._
import service.notifications.{
  DevelopmentEmailNotifications,
  EmailNotifications,
  Notification,
  PagerdutyNotifications
}
import software.amazon.awssdk.services.batch.BatchClient
import software.amazon.awssdk.services.cloudformation.CloudFormationClient
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.emr.EmrClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.ses.SesClient

@Singleton
class PrometheusJmxInstrumentation @Inject()(implicit val registry: Registry) {
  jmx.register
}

class Config(environment: Environment, configuration: Configuration)
    extends AbstractModule {

  override def configure(): Unit = {

    Logger.info(s" *** Starting Sundial *** ")

    Logger.info("Env Variables:")
    sys.env.foreach {
      case (key, value) => Logger.info(s"Key($key), Value($value)")
    }

    Logger.info("Sundial Configuration:")
    configuration.entrySet.foreach { entry =>
      Logger.info(s"Key(${entry._1}), Value[${entry._2.toString}]")
    }

    bind(classOf[Registry]).toInstance(DefaultRegistry())

    // AWS Clients
    bind(classOf[BatchClient]).toInstance(BatchClient.create())
    bind(classOf[S3Client]).toInstance(S3Client.create())
    bind(classOf[CloudWatchLogsClient])
      .toInstance(CloudWatchLogsClient.create())
    bind(classOf[Ec2Client]).toInstance(Ec2Client.create())
    bind(classOf[EmrClient]).toInstance(EmrClient.create())
    bind(classOf[CloudFormationClient])
      .toInstance(CloudFormationClient.create())
    bind(classOf[SesClient])
      .toInstance(SesClient.create())

    bind(classOf[PrometheusJmxInstrumentation]).asEagerSingleton()

    bind(classOf[Sundial]).asEagerSingleton()

  }

  @Provides
  @Singleton
  def notifications(wsClient: WSClient,
                    daoFactory: SundialDaoFactory,
                    displayModels: DisplayModels,
                    sesClient: SesClient): Seq[Notification] = {
    configuration.getOptional[String]("notifications.mode") match {
      case Some("browser") =>
        Seq(
          new DevelopmentEmailNotifications(daoFactory,
                                            displayModels,
                                            sesClient))
      case Some("email") =>
        Seq(
          new EmailNotifications(
            daoFactory,
            configuration.get[String]("notifications.from"),
            displayModels,
            sesClient))
      case Some("all") =>
        Seq(
          new EmailNotifications(
            daoFactory,
            configuration.get[String]("notifications.from"),
            displayModels,
            sesClient),
          new PagerdutyNotifications(wsClient, daoFactory)
        )
      case _ => Seq.empty
    }
  }

}
