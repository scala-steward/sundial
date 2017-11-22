name := """svc-sundial"""

version := "1.0.0"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala)

scalaVersion := "2.11.12"

val prometheusLibVersion = "0.8.4"

libraryDependencies ++= Seq(
    "com.amazonaws"                % "aws-java-sdk"              % "1.11.184",
    "commons-io"                   % "commons-io"                % "2.4",             // for utility functions
    "org.quartz-scheduler"         % "quartz"                    % "2.2.1",           // used only for CronExpression.getNextValidTimeAfter
    "org.postgresql"               % "postgresql"                % "42.1.4",
    "com.fasterxml.jackson.module" %% "jackson-module-scala"     % "2.8.7",           // only for JSON serialization for PostgreSQL
    "org.apache.commons"           % "commons-compress"          % "1.9",
    "org.scalatestplus.play"       %% "scalatestplus-play"       % "2.0.0" % "test",
    "org.lyranthe.prometheus" %% "client" % prometheusLibVersion,
    "org.lyranthe.prometheus" %% "play25" % prometheusLibVersion,
    jdbc,
    evolutions,
    "com.typesafe.play" %% "anorm" % "2.5.3",
    "com.typesafe.play" %% "play-ws" % "2.5.18",
    "com.typesafe.play" %% "play-cache" % "2.5.18",
    "org.mockito" % "mockito-all" % "1.10.19" % "test"
  )

routesImport += "com.gilt.svc.sundial.v0.Bindables._"

unmanagedResourceDirectories in Test <+=  baseDirectory (_ /"target/web/public/test")

javaOptions in Test ++= Seq(
  "-Dconfig.file=conf/application.test.conf"
)
