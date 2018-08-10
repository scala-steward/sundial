package config

import javax.inject.Inject

import com.amazonaws.services.batch.{AWSBatch, AWSBatchClientBuilder}
import com.amazonaws.services.cloudformation.{
  AmazonCloudFormation,
  AmazonCloudFormationClientBuilder
}
import com.amazonaws.services.ec2.{AmazonEC2, AmazonEC2ClientBuilder}
import com.amazonaws.services.ecs.model.{
  PlacementStrategy,
  PlacementStrategyType
}
import com.amazonaws.services.ecs.{AmazonECS, AmazonECSClientBuilder}
import com.amazonaws.services.logs.{AWSLogs, AWSLogsClientBuilder}
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.amazonaws.services.simpledb.{
  AmazonSimpleDB,
  AmazonSimpleDBClientBuilder
}
import com.amazonaws.services.simpleemail.{
  AmazonSimpleEmailServiceAsync,
  AmazonSimpleEmailServiceAsyncClientBuilder
}
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
    bind(classOf[AWSBatch]).toInstance(AWSBatchClientBuilder.defaultClient())
    bind(classOf[AmazonS3]).toInstance(AmazonS3ClientBuilder.defaultClient())
    bind(classOf[AWSLogs]).toInstance(AWSLogsClientBuilder.defaultClient())
    bind(classOf[AmazonECS]).toInstance(AmazonECSClientBuilder.defaultClient())
    bind(classOf[AmazonEC2]).toInstance(AmazonEC2ClientBuilder.defaultClient())
    bind(classOf[AmazonCloudFormation])
      .toInstance(AmazonCloudFormationClientBuilder.defaultClient())
    bind(classOf[AmazonSimpleDB])
      .toInstance(AmazonSimpleDBClientBuilder.defaultClient())
    bind(classOf[AmazonSimpleEmailServiceAsync])
      .toInstance(AmazonSimpleEmailServiceAsyncClientBuilder.defaultClient())

    bind(classOf[PrometheusJmxInstrumentation]).asEagerSingleton()

    bind(classOf[Sundial]).asEagerSingleton()

  }

  @Provides
  @Singleton
  def notifications(
      wsClient: WSClient,
      daoFactory: SundialDaoFactory,
      displayModels: DisplayModels,
      sesClient: AmazonSimpleEmailServiceAsync): Seq[Notification] = {
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

  @Provides
  @Singleton
  def taskPlacementStrategy(): PlacementStrategy = {
    val taskPlacementString = configuration
      .getOptional[String]("ecs.defaultTaskPlacement")
      .getOrElse("random")
      .toLowerCase
    val binpackPlacement = configuration
      .getOptional[String]("ecs.binpackPlacement")
      .getOrElse("memory")
      .toLowerCase
    val spreadPlacement = configuration
      .getOptional[String]("ecs.spreadPlacement")
      .getOrElse("host")
      .toLowerCase
    val placementStrategyType =
      PlacementStrategyType.fromValue(taskPlacementString)
    val placementStrategy =
      new PlacementStrategy().withType(placementStrategyType)
    placementStrategyType match {
      case PlacementStrategyType.Binpack =>
        placementStrategy.withField(binpackPlacement)
      case PlacementStrategyType.Random => placementStrategy
      case PlacementStrategyType.Spread =>
        placementStrategy.withField(spreadPlacement)
    }
  }

}
