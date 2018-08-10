package config

import javax.inject.Named

import com.google.inject.{AbstractModule, Provides, Singleton}
import play.api.{Configuration, Environment}

class DevelopmentConfig(environment: Environment, configuration: Configuration)
    extends AbstractModule {

  override def configure() = {}

  @Provides
  @Named("cfnStackName")
  @Singleton
  def cfnStackName: String = "dumm-stack-name"

  @Provides
  @Named("s3Bucket")
  @Singleton
  def s3Bucket: String = configuration.get[String]("s3.bucket")

  @Provides
  @Named("sdbDomain")
  @Singleton
  def sdbDomain: String = "dummy-sdb-domain"

  @Provides
  @Named("sundialUrl")
  @Singleton
  def sundialUrl: String = "http://localhost:9000"

}
