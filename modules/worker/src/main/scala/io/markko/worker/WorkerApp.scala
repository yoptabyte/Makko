package io.markko.worker

import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import io.prometheus.client.exporter.HTTPServer
import io.markko.worker.actors.{ClusterSupervisor, ParserSupervisor, ResyncActor}

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

object WorkerApp extends LazyLogging {

  def main(args: Array[String]): Unit = {

    val config = ConfigFactory.load()

    val clusterMode = sys.env.getOrElse("MARKKO_CLUSTER_MODE", "false").toBoolean

    logger.info(s"Starting Markko Worker (cluster=$clusterMode)...")

    val metricsPort = sys.env.get("MARKKO_WORKER_METRICS_PORT").flatMap(_.toIntOption).getOrElse(9095)
    val metricsServer = new HTTPServer(metricsPort)
    logger.info(s"Worker metrics exposed on http://0.0.0.0:$metricsPort/metrics")

    val system: ActorSystem[Nothing] = if (clusterMode) {
      ActorSystem[Nothing](ClusterSupervisor(config), "markko-worker", config)
    } else {
      val rootBehavior: Behavior[Nothing] = Behaviors.setup[Nothing] { context =>
        context.spawn(ParserSupervisor(config), "parser-supervisor")
        context.spawn(ResyncActor(config), "resync-actor")
        Behaviors.empty
      }
      ActorSystem[Nothing](rootBehavior, "markko-worker", config)
    }

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

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      logger.info("Shutting down Markko Worker...")
      metricsServer.stop()
      system.terminate()
      Await.result(system.whenTerminated, 30.seconds)
      logger.info("Markko Worker stopped.")
    }))

    logger.info(s"Markko Worker started. Mode: ${if (clusterMode) "CLUSTER" else "LOCAL"}")
    Await.result(system.whenTerminated, Duration.Inf)
  }
}
