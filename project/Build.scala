import play.{PlayScala, PlayImport}
import play.PlayImport.PlayKeys._
import sbt._
import Keys._

object SundialBuild extends Build {

  val dependencies = Seq(
    "com.amazonaws"                % "aws-java-sdk"              % "1.11.20",
    "commons-io"                   % "commons-io"                % "2.4",             // for utility functions
    "org.quartz-scheduler"         % "quartz"                    % "2.2.1",           // used only for CronExpression.getNextValidTimeAfter
    "org.postgresql"               % "postgresql"                % "9.4-1201-jdbc41",
    "com.fasterxml.jackson.module" %% "jackson-module-scala"     % "2.7.5",           // only for JSON serialization for PostgreSQL
    "org.apache.commons"           % "commons-compress"          % "1.9",
    "org.scalatestplus"            %% "play"                     % "1.1.1" % "test",
    PlayImport.jdbc,
    PlayImport.anorm,
    PlayImport.cache,
    PlayImport.ws
  )

  lazy val root = Project("svc-sundial", file("."))
    .enablePlugins(PlayScala)
    .settings(
      version := "0.0.7",
      scalaVersion := "2.11.8",
      unmanagedResourceDirectories in Test <+=  baseDirectory (_ /"target/web/public/test"),
      libraryDependencies ++= dependencies,
      routesImport += "com.gilt.svc.sundial.v0.Bindables._",
      javaOptions in Test ++= Seq(
        "-Dconfig.file=conf/application.test.conf"
      )
    )
}
