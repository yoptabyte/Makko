// =============================================================================
// Markko — build.sbt
// Scala 2.13 · Play · Pekko Cluster · Redis · MySQL · Doobie · Elasticsearch
// =============================================================================

ThisBuild / scalaVersion     := "2.13.14"
ThisBuild / organization     := "io.markko"
ThisBuild / version          := "0.1.0-SNAPSHOT"

// Shared compiler settings for every module
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-language:higherKinds",
  "-language:implicitConversions"
)

// =============================================================================
// Dependency versions
// =============================================================================

val pekkoVersion           = "1.0.3"
val pekkoHttpVersion       = "1.0.1"
val doobieVersion          = "1.0.0-RC4"
val catsVersion            = "2.10.0"
val catsEffectVersion      = "3.5.4"
val redis4catsVersion      = "1.7.1"
val elastic4sVersion       = "8.11.5"
val flywayVersion          = "10.10.0"
val mysqlConnectorVersion  = "8.3.0"
val flexmarkVersion        = "0.64.8"
val jsoupVersion           = "1.17.2"
val prometheusVersion      = "0.16.0"
val testcontainersVersion  = "0.44.1"
val testcontainersJavaVersion = "1.21.4"
val logbackVersion         = "1.5.3"
val playVersion            = "3.0.2"
val silhouetteVersion      = "10.0.0"
val jwtScalaVersion        = "10.0.0"
val jacksonVersion         = "2.15.2"

ThisBuild / dependencyOverrides ++= Seq(
  "com.fasterxml.jackson.core"   % "jackson-annotations"   % jacksonVersion,
  "com.fasterxml.jackson.core"   % "jackson-core"          % jacksonVersion,
  "com.fasterxml.jackson.core"   % "jackson-databind"      % jacksonVersion,
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonVersion
)

// =============================================================================
// Aggregating root project
// =============================================================================

lazy val root = (project in file("."))
  .aggregate(api, worker, shared)
  .settings(
    name := "markko",
    // Do not publish the aggregate root
    publish / skip := true
  )

// =============================================================================
// Shared: domain models, config, and shared utilities
// =============================================================================

lazy val shared = (project in file("modules/shared"))
  .settings(
    name := "markko-shared",
    libraryDependencies ++= Seq(
      "org.playframework" %% "play-json" % "3.0.2",

      // Cats
      "org.typelevel" %% "cats-core"   % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,

      // Pekko core
      "org.apache.pekko" %% "pekko-actor-typed"  % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream"       % pekkoVersion,

      // Circe (JSON)
      "io.circe" %% "circe-core"    % "0.14.6",
      "io.circe" %% "circe-generic" % "0.14.6",
      "io.circe" %% "circe-parser"  % "0.14.6",
      
      // Doobie type support
      "org.tpolecat" %% "doobie-core" % doobieVersion,

      // Elasticsearch client helpers
      "com.sksamuel.elastic4s" %% "elastic4s-core"          % elastic4sVersion,
      "com.sksamuel.elastic4s" %% "elastic4s-client-esjava" % elastic4sVersion,

      // Logging
      "ch.qos.logback" % "logback-classic" % logbackVersion,
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5"
    )
  )

// =============================================================================
// API: Play Framework HTTP service
// =============================================================================

lazy val api = (project in file("modules/api"))
  .enablePlugins(PlayScala)
  .dependsOn(shared)
  .settings(
    name := "markko-api",
    libraryDependencies ++= Seq(
      // Play
      guice,
      ws,
      "org.playframework" %% "play"              % playVersion,
      "org.playframework" %% "play-json"         % "3.0.2",

      // Silhouette + JWT
      "org.playframework.silhouette" %% "play-silhouette"                 % silhouetteVersion,
      "org.playframework.silhouette" %% "play-silhouette-password-bcrypt" % silhouetteVersion,
      "org.playframework.silhouette" %% "play-silhouette-persistence"     % silhouetteVersion,
      "org.playframework.silhouette" %% "play-silhouette-crypto-jca"       % silhouetteVersion,
      
      // JWT (jwt-scala)
      "com.github.jwt-scala" %% "jwt-core" % jwtScalaVersion,
      
      // Password hashing
      "at.favre.lib" % "bcrypt" % "0.10.2",

      // Pekko Cluster for worker coordination
      "org.apache.pekko" %% "pekko-cluster-typed"         % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-sharding-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-remote"                 % pekkoVersion,
      "org.apache.pekko" %% "pekko-serialization-jackson"  % pekkoVersion,

      // Doobie + MySQL
      "org.tpolecat"  %% "doobie-core"      % doobieVersion,
      "org.tpolecat"  %% "doobie-hikari"    % doobieVersion,
      "org.tpolecat"  %% "doobie-specs2"    % doobieVersion % Test,
      "com.mysql"      % "mysql-connector-j"    % mysqlConnectorVersion,

      // Flyway migrations
      "org.flywaydb"  % "flyway-core"       % flywayVersion,
      "org.flywaydb"  % "flyway-mysql"      % flywayVersion,

      // Redis (redis4cats)
      "dev.profunktor" %% "redis4cats-effects"  % redis4catsVersion,
      "dev.profunktor" %% "redis4cats-streams"  % redis4catsVersion,
      "dev.profunktor" %% "redis4cats-log4cats" % redis4catsVersion,

      // Elasticsearch
      "com.sksamuel.elastic4s" %% "elastic4s-core"          % elastic4sVersion,
      "com.sksamuel.elastic4s" %% "elastic4s-client-esjava" % elastic4sVersion,
      "com.sksamuel.elastic4s" %% "elastic4s-json-circe"    % elastic4sVersion,

      // Prometheus metrics
      "io.prometheus" % "simpleclient"            % prometheusVersion,
      "io.prometheus" % "simpleclient_hotspot"    % prometheusVersion,
      "io.prometheus" % "simpleclient_httpserver" % prometheusVersion,

      // Tests
      "org.scalatest" %% "scalatest" % "3.2.18" % Test,
      "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.1" % Test
    ),

    // Play routes
    routesGenerator := InjectedRoutesGenerator,

    // Play uses `conf/` as its main resource directory, so include the standard
    // JVM resources directory explicitly for Flyway migrations and other assets.
    Compile / unmanagedResourceDirectories += baseDirectory.value / "src/main/resources",

    // Keep a standard src/test layout for the API module.
    Test / scalaSource := baseDirectory.value / "src/test/scala",
    Test / javaSource := baseDirectory.value / "src/test/java",
    Test / resourceDirectory := baseDirectory.value / "src/test/resources",

    // MinIO already occupies 9000 and 9001 in this environment, so run Play on 9002 by default.
    PlayKeys.playDefaultPort := 9002,

    // Skip docs to keep local builds faster
    Compile / doc / sources := Seq.empty
  )

lazy val apiIntegration = (project in file("modules/api-it"))
  .dependsOn(api)
  .settings(
    name := "markko-api-integration",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.18" % Test,
      "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.1" % Test,
      "com.dimafeng" %% "testcontainers-scala-mysql"         % testcontainersVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-elasticsearch" % testcontainersVersion % Test,
      "org.testcontainers" % "testcontainers" % testcontainersJavaVersion % Test,
      "org.testcontainers" % "mysql"          % testcontainersJavaVersion % Test,
      "org.testcontainers" % "jdbc"           % testcontainersJavaVersion % Test,
      "org.testcontainers" % "database-commons" % testcontainersJavaVersion % Test
    ),
    Test / scalaSource := baseDirectory.value / "src/test/scala",
    Test / javaSource := baseDirectory.value / "src/test/java",
    Test / resourceDirectory := baseDirectory.value / "src/test/resources",
    publish / skip := true
  )

// =============================================================================
// Worker: Pekko-based parser and exporter runtime
// =============================================================================

lazy val worker = (project in file("modules/worker"))
  .dependsOn(shared)
  .settings(
    name := "markko-worker",
    libraryDependencies ++= Seq(
      // Pekko Cluster worker runtime
      "org.apache.pekko" %% "pekko-actor-typed"             % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-typed"           % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-sharding-typed"  % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream"                  % pekkoVersion,
      "org.apache.pekko" %% "pekko-remote"                  % pekkoVersion,
      "org.apache.pekko" %% "pekko-serialization-jackson"   % pekkoVersion,

      // Pekko HTTP for downloading pages and images
      "org.apache.pekko" %% "pekko-http"            % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,

      // HTML parsing
      "org.jsoup"         % "jsoup"                 % jsoupVersion,

      // HTML to Markdown conversion for Obsidian export
      "com.vladsch.flexmark" % "flexmark"                       % flexmarkVersion,
      "com.vladsch.flexmark" % "flexmark-html2md-converter"     % flexmarkVersion,
      "com.vladsch.flexmark" % "flexmark-util-ast"              % flexmarkVersion,
      "com.vladsch.flexmark" % "flexmark-util-data"             % flexmarkVersion,
      "com.vladsch.flexmark" % "flexmark-ext-tables"            % flexmarkVersion,
      "com.vladsch.flexmark" % "flexmark-ext-gfm-strikethrough" % flexmarkVersion,
      "com.vladsch.flexmark" % "flexmark-ext-autolink"          % flexmarkVersion,

      // Doobie persistence for MySQL writes
      "org.tpolecat"  %% "doobie-core"           % doobieVersion,
      "org.tpolecat"  %% "doobie-hikari"         % doobieVersion,
      "com.mysql"      % "mysql-connector-j"     % mysqlConnectorVersion,

      // Redis queues and pub/sub
      "dev.profunktor" %% "redis4cats-effects"   % redis4catsVersion,
      "dev.profunktor" %% "redis4cats-streams"   % redis4catsVersion,

      // Elasticsearch indexing
      "com.sksamuel.elastic4s" %% "elastic4s-core"          % elastic4sVersion,
      "com.sksamuel.elastic4s" %% "elastic4s-client-esjava" % elastic4sVersion,

      // Prometheus
      "io.prometheus" % "simpleclient"         % prometheusVersion,
      "io.prometheus" % "simpleclient_hotspot" % prometheusVersion,
      "io.prometheus" % "simpleclient_httpserver" % prometheusVersion,

      // Tests
      "org.apache.pekko"  %% "pekko-actor-testkit-typed" % pekkoVersion % Test,
      "org.scalatest"     %% "scalatest"                 % "3.2.18"     % Test,
      "com.dimafeng"      %% "testcontainers-scala-mysql" % testcontainersVersion % Test
    ),

    // Worker entry point
    Compile / mainClass := Some("io.markko.worker.WorkerApp")
  )

// =============================================================================
// Convenience aliases
// =============================================================================

addCommandAlias("runApi",    "api/run")
addCommandAlias("runWorker", "worker/run")
addCommandAlias("testAll",   "test")
addCommandAlias("testIntegration", "apiIntegration/test")
addCommandAlias("fmt",       "scalafmt")
addCommandAlias("fmtCheck",  "scalafmtCheck")

// =============================================================================
// sbt plugins (see project/plugins.sbt)
// =============================================================================
// addSbtPlugin("org.playframework" % "sbt-plugin"      % playVersion)
// addSbtPlugin("org.scalameta"     % "sbt-scalafmt"    % "2.5.2")
// addSbtPlugin("com.eed3si9n"      % "sbt-assembly"    % "2.2.0")   // fat JAR for the worker
// addSbtPlugin("io.spray"          % "sbt-revolver"    % "0.10.0")  // hot reload
