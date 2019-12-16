import scoverage.ScoverageKeys
import scala.sys.process._

name := """sundial"""

version := "git describe --tags --dirty --always".!!.stripPrefix("v").trim

enablePlugins(PlayScala, PlayAkkaHttpServer)
disablePlugins(PlayNettyServer)

scalaVersion := "2.12.8"

val prometheusLibVersion = "0.9.0-M5"

val awsVersion = "2.7.8"

scalafmtOnCompile := true

libraryDependencies ++= Seq(
    jdbc,
    guice,
    evolutions,
    ws,
  "org.playframework.anorm" %% "anorm" % "2.6.4",
    "software.amazon.awssdk"       % "emr"              % awsVersion,
    "software.amazon.awssdk"       % "s3"               % awsVersion,
    "software.amazon.awssdk"       % "batch"            % awsVersion,
    "software.amazon.awssdk"       % "ses"              % awsVersion,
    "software.amazon.awssdk"       % "cloudwatchlogs"       % awsVersion,
    "software.amazon.awssdk"       % "cloudformation"   % awsVersion,
    "software.amazon.awssdk"       % "ec2"              % awsVersion,
    "commons-io"                   % "commons-io"                % "2.6",             // for utility functions
    "org.quartz-scheduler"         % "quartz"                    % "2.3.1",           // used only for CronExpression.getNextValidTimeAfter
    "com.fasterxml.jackson.module" %% "jackson-module-scala"     % "2.9.9",           // only for JSON serialization for PostgreSQL
    "org.postgresql"               % "postgresql"                % "42.2.6",
    "org.apache.commons"           % "commons-compress"          % "1.18",
    "org.lyranthe.prometheus" %% "client" % prometheusLibVersion,
    "org.lyranthe.prometheus" %% "play26" % prometheusLibVersion,
    "org.apache.commons" % "commons-text" % "1.7",
    "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.0",
    "org.scalatestplus.play"       %% "scalatestplus-play"       % "4.0.3" % "test",
    "org.mockito" % "mockito-core" % "3.2.4" % "test"
)

routesImport ++= Seq("com.hbc.svc.sundial.v2.Bindables.Core._", "com.hbc.svc.sundial.v2.Bindables.Models._")

javaOptions in Test ++= Seq(
  "-Dconfig.file=conf/application.test.conf"
)


import com.typesafe.sbt.packager.docker._
dockerBaseImage := "openjdk:8-jre"

dockerCommands := (
  Seq(dockerCommands.value.head) ++ Seq(
    ExecCmd("RUN", "apt-get", "update"),
    ExecCmd("RUN", "apt-get", "install", "-y", "graphviz"),
    ExecCmd("RUN", "apt-get", "clean"),
    ExecCmd("RUN", "rm", "-rf", "/var/lib/apt/lists/*", "/tmp/*", "/var/tmp/*")
  ) ++ dockerCommands.value.tail)

bashScriptExtraDefines ++= Seq(
  """addJava "-Dplay.evolutions.db.default.autoApply=true"""",
  """addJava "-Dconfig.resource=application.prod.conf""""
)
