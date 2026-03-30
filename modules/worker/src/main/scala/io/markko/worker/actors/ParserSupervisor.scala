package io.markko.worker.actors

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import io.markko.shared.redis.{RedisConfigSupport, RedisKeys}

import scala.concurrent.duration._

/**
 * Supervisor that polls Redis queue and delegates to ParserWorker and ExporterWorker.
 * Flow: Redis queue → ParserWorker → ExporterWorker → done
 */
object ParserSupervisor extends LazyLogging {

  sealed trait Command
  case object PollQueue extends Command
  case class ParseComplete(linkId: Long, success: Boolean) extends Command
  case class ExportComplete(linkId: Long, success: Boolean) extends Command
  case object GracefulShutdown extends Command

  private case object PollTimerKey

  def apply(config: Config): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        val parser   = context.spawn(ParserWorker(config), "parser-worker")
        val exporter = context.spawn(ExporterWorker(config), "exporter-worker")

        // Poll every 2 seconds
        timers.startTimerWithFixedDelay(PollTimerKey, PollQueue, 2.seconds)

        logger.info("ParserSupervisor started with parser + exporter workers, polling every 2s")
        running(config, parser, exporter, timers)
      }
    }
  }

  private def running(
    config:   Config,
    parser:   ActorRef[ParserWorker.Command],
    exporter: ActorRef[ExporterWorker.Command],
    timers:   TimerScheduler[Command]
  ): Behavior[Command] = {
    Behaviors.receive { (context, message) =>
      message match {
        case PollQueue =>
          import cats.effect.unsafe.implicits.global
          import dev.profunktor.redis4cats.Redis
          import dev.profunktor.redis4cats.effect.Log.Stdout._

          // Poll all 3 queues
          val (parseJob, exportJob, deleteJob) = Redis[cats.effect.IO].utf8(RedisConfigSupport.connectionUri(config)).use { redis =>
            for {
              p <- redis.lPop(RedisKeys.ParseQueue)
              e <- redis.lPop(RedisKeys.ExportQueue)
              d <- redis.lPop(RedisKeys.DeleteQueue)
            } yield (p, e, d)
          }.unsafeRunSync()

          // Dispatch parse jobs
          parseJob.flatMap(s => scala.util.Try(s.toLong).toOption).foreach { linkId =>
            logger.info(s"Dequeued parse job for link $linkId")
            parser ! ParserWorker.Parse(linkId, context.self)
          }

          // Dispatch re-export jobs (from API POST /export/:id/reexport)
          exportJob.flatMap(s => scala.util.Try(s.toLong).toOption).foreach { linkId =>
            logger.info(s"Dequeued re-export job for link $linkId")
            exporter ! ExporterWorker.ReExport(linkId, context.self)
          }

          // Dispatch delete jobs (from API DELETE /export/:id)
          deleteJob.flatMap(s => scala.util.Try(s.toLong).toOption).foreach { linkId =>
            logger.info(s"Dequeued delete job for link $linkId")
            exporter ! ExporterWorker.DeleteExport(linkId, context.self)
          }

          Behaviors.same

        case ParseComplete(linkId, success) =>
          if (success) {
            logger.info(s"Link $linkId parsed — sending to exporter")
            exporter ! ExporterWorker.Export(linkId, context.self)
          } else {
            logger.warn(s"Link $linkId parsing failed — skipping export")
          }
          Behaviors.same

        case ExportComplete(linkId, success) =>
          if (success)
            logger.info(s"Link $linkId fully exported to vault")
          else
            logger.warn(s"Link $linkId export failed")
          Behaviors.same

        case GracefulShutdown =>
          logger.info("ParserSupervisor shutting down gracefully...")
          timers.cancelAll()
          Behaviors.stopped
      }
    }
  }
}
