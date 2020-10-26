logLevel := Level.Warn

resolvers += "Typesafe repository" at "https://repo.typesafe.com/typesafe/releases/"

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.7.6")

addSbtPlugin("org.scoverage" %% "sbt-scoverage" % "1.5.1")

addSbtPlugin("org.scoverage" %% "sbt-coveralls" % "1.2.2")

addSbtPlugin("com.geirsson" % "sbt-scalafmt" % "1.5.1")
