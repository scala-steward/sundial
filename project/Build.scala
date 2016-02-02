import play.{PlayScala, PlayImport}
import play.PlayImport.PlayKeys._
import sbt._
import Keys._

object SundialBuild extends Build {

  val dependencies = Seq(
    "com.amazonaws"                % "aws-java-sdk"              % "1.10.35",
    "commons-io"                   % "commons-io"                % "2.4",             // for utility functions
    "org.quartz-scheduler"         % "quartz"                    % "2.2.1",           // used only for CronExpression.getNextValidTimeAfter
    "org.postgresql"               % "postgresql"                % "9.4-1201-jdbc41",
    "com.fasterxml.jackson.module" %% "jackson-module-scala"     % "2.6.3",           // only for JSON serialization for PostgreSQL
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
      version := "0.0.2",
      scalaVersion := "2.11.6",  // 2.11.7 triggers weird issue with parsing routes file (https://gitter.im/playframework/playframework/archives/2015/07/06)
      scalacOptions ++= Seq("-target:jvm-1.7", "-Xlint", "-Xfatal-warnings", "-feature"),
      javacOptions ++= Seq("-source", "1.7", "-target", "1.7"),
      unmanagedResourceDirectories in Test <+=  baseDirectory (_ /"target/web/public/test"),
      libraryDependencies ++= dependencies,
      routesImport += "com.gilt.svc.sundial.v0.Bindables._",
      javaOptions in Test ++= Seq(
        "-Dconfig.file=conf/application.test.conf"
      )
    )
}
