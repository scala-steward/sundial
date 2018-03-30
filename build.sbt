import scoverage.ScoverageKeys

name := """sundial"""

version := "git describe --tags --dirty --always".!!.stripPrefix("v").trim

enablePlugins(PlayScala, PlayAkkaHttpServer)
disablePlugins(PlayNettyServer)

scalaVersion := "2.11.12"

val prometheusLibVersion = "0.8.4"

libraryDependencies ++= Seq(
    jdbc,
    evolutions,
    ws,
    cache,
    "com.typesafe.play" %% "anorm" % "2.5.3",
    "com.amazonaws"                % "aws-java-sdk"              % "1.11.248",
    "commons-io"                   % "commons-io"                % "2.4",             // for utility functions
    "org.quartz-scheduler"         % "quartz"                    % "2.2.1",           // used only for CronExpression.getNextValidTimeAfter
    "org.postgresql"               % "postgresql"                % "42.1.4",
    "com.fasterxml.jackson.module" %% "jackson-module-scala"     % "2.8.7",           // only for JSON serialization for PostgreSQL
    "org.apache.commons"           % "commons-compress"          % "1.9",
    "org.lyranthe.prometheus" %% "client" % prometheusLibVersion,
    "org.lyranthe.prometheus" %% "play25" % prometheusLibVersion,
    "org.scalatestplus.play"       %% "scalatestplus-play"       % "2.0.0" % "test",
    "org.mockito" % "mockito-all" % "1.10.19" % "test"
  )

routesImport ++= Seq("com.gilt.svc.sundial.v0.Bindables.Core._", "com.gilt.svc.sundial.v0.Bindables.Models._")

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
