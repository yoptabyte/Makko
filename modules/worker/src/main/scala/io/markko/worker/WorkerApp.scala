package io.markko.worker

import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import io.markko.worker.actors.{ClusterSupervisor, ParserSupervisor, ResyncActor}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

/**
 * Entry point for the Markko Worker node.
 *
 * Supports two modes:
 * - Local (default): single-node, no cluster
 * - Cluster: Pekko Cluster with ClusterSingleton
 *
 * Set MARKKO_CLUSTER_MODE=true to enable cluster mode.
 */
object WorkerApp extends App with LazyLogging {

  val config = ConfigFactory.load()

  val clusterMode = sys.env.getOrElse("MARKKO_CLUSTER_MODE", "false").toBoolean

  logger.info(s"Starting Markko Worker (cluster=$clusterMode)...")

  val system: ActorSystem[Nothing] = if (clusterMode) {
    // Cluster mode: ClusterSingleton for ParserSupervisor, local ResyncActor
    ActorSystem[Nothing](ClusterSupervisor(config), "markko-worker", config)
  } else {
    // Local mode: direct actor spawning
    val rootBehavior: Behavior[Nothing] = Behaviors.setup[Nothing] { context =>
      context.spawn(ParserSupervisor(config), "parser-supervisor")
      context.spawn(ResyncActor(config), "resync-actor")
      Behaviors.empty
    }
    ActorSystem[Nothing](rootBehavior, "markko-worker", config)
  }

  // Track uptime
  val startTime = System.currentTimeMillis()
  val uptimeThread = new Thread(() => {
    while (!Thread.interrupted()) {
      io.markko.worker.services.WorkerMetrics.workerUptime.set(
        (System.currentTimeMillis() - startTime) / 1000.0
      )
      Thread.sleep(5000)
    }
  }, "uptime-tracker")
  uptimeThread.setDaemon(true)
  uptimeThread.start()

  // Graceful shutdown
  sys.addShutdownHook {
    logger.info("Shutting down Markko Worker...")
    system.terminate()
  }

  logger.info(s"Markko Worker started. Mode: ${if (clusterMode) "CLUSTER" else "LOCAL"}")
  Await.result(system.whenTerminated, Duration.Inf)
}
