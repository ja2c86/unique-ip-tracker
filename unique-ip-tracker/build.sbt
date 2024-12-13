import sbt.enablePlugins

val scala3Version = "3.5.2"

val http4sVersion         = "0.23.30"
val circeVersion          = "0.14.10"
val mongo4catsVersion     = "0.7.11"
val fs2KafkaVersion       = "3.6.0"
val log4catsVersion       = "2.7.0"
val logbackVersion        = "1.5.12"
val weaverVersion         = "0.8.4"
val testContainersVersion = "0.41.4"

enablePlugins(DockerPlugin)
enablePlugins(AshScriptPlugin)

lazy val root = project
  .in(file("."))
  .settings(
    name := "Unique IP Tracker",
    version := "0.1.0-SNAPSHOT",

    scalaVersion := scala3Version,

    libraryDependencies ++= Seq(
      "org.http4s"          %%  "http4s-ember-server"           % http4sVersion,
      "org.http4s"          %%  "http4s-ember-client"           % http4sVersion,
      "org.http4s"          %%  "http4s-dsl"                    % http4sVersion,
      "org.http4s"          %%  "http4s-circe"                  % http4sVersion,

      "io.circe"            %%  "circe-core"                    % circeVersion,
      "io.circe"            %%  "circe-generic"                 % circeVersion,
      "io.circe"            %%  "circe-parser"                  % circeVersion,

      "io.github.kirill5k"  %%  "mongo4cats-core"               % mongo4catsVersion,
      "io.github.kirill5k"  %%  "mongo4cats-circe"              % mongo4catsVersion,

      "com.github.fd4s"     %%  "fs2-kafka"                     % fs2KafkaVersion,

      "org.typelevel"       %%  "log4cats-core"                 % log4catsVersion,
      "org.typelevel"       %%  "log4cats-slf4j"                % log4catsVersion,
      "ch.qos.logback"      %   "logback-classic"               % logbackVersion,

      "com.disneystreaming" %%  "weaver-cats"                   % weaverVersion         % Test,
      "com.disneystreaming" %%  "weaver-scalacheck"             % weaverVersion         % Test,
      "com.dimafeng"        %%  "testcontainers-scala-core"     % testContainersVersion % Test,
      "com.dimafeng"        %%  "testcontainers-scala-mongodb"  % testContainersVersion % Test,
      "com.dimafeng"        %%  "testcontainers-scala-kafka"    % testContainersVersion % Test
    ),

    testFrameworks += new TestFramework("weaver.framework.CatsEffect"),

    Compile / mainClass := Some("Main"),

    Docker / packageName := "unique-ip-tracker",
    dockerBaseImage := "eclipse-temurin:21-jdk-jammy",
    dockerExposedPorts ++= Seq(8080),
    dockerUpdateLatest := true
  )
