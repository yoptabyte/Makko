package io.markko.worker.actors

import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import dev.profunktor.redis4cats.RedisCommands
import io.markko.shared.redis.{RedisConfigSupport, RedisKeys}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.effect.Log.NoOp._

import scala.concurrent.duration._
import scala.util.{Success, Failure}

object ParserSupervisor extends LazyLogging {

  sealed trait Command
  case object PollQueue extends Command
  case class ParseComplete(linkId: Long, success: Boolean) extends Command
  case class ExportComplete(linkId: Long, success: Boolean) extends Command
  case object GracefulShutdown extends Command
  private case class QueuesPolled(parseJob: Option[String], exportJob: Option[String], deleteJob: Option[String]) extends Command

  private case object PollTimerKey

  def apply(config: Config): Behavior[Command] = {
    Behaviors.supervise(
      Behaviors.setup[Command] { context =>
        val redisUri = RedisConfigSupport.connectionUri(config)

        val (redis: RedisCommands[IO, String, String], shutdown: IO[Unit]) =
          Redis[IO].utf8(redisUri).allocated.unsafeRunSync()

        Behaviors.withTimers { timers =>
          val parser   = context.spawn(ParserWorker(config), "parser-worker")
          val exporter = context.spawn(ExporterWorker(config), "exporter-worker")

          timers.startTimerWithFixedDelay(PollTimerKey, PollQueue, 2.seconds)

          logger.info("ParserSupervisor started with parser + exporter workers, polling every 2s")
          running(config, parser, exporter, redis, busy = false, shutdown)
        }
      }
    ).onFailure[Exception](SupervisorStrategy.restartWithBackoff(1.second, 30.seconds, 0.2))
  }

  private def running(
    config:    Config,
    parser:    ActorRef[ParserWorker.Command],
    exporter:  ActorRef[ExporterWorker.Command],
    redis:     RedisCommands[IO, String, String],
    busy:      Boolean,
    shutdown:  IO[Unit]
  ): Behavior[Command] = {
    Behaviors.receive { (context, message) =>
      message match {
        case PollQueue if !busy =>
          val pollIO = for {
            p <- redis.lPop(RedisKeys.ParseQueue)
            e <- redis.lPop(RedisKeys.ExportQueue)
            d <- redis.lPop(RedisKeys.DeleteQueue)
          } yield (p, e, d)

          context.pipeToSelf(pollIO.unsafeToFuture()) {
            case Success((p, e, d)) => QueuesPolled(p, e, d)
            case Failure(_)         => QueuesPolled(None, None, None)
          }

          running(config, parser, exporter, redis, busy = true, shutdown)

        case PollQueue =>
          Behaviors.same

        case QueuesPolled(parseJob, exportJob, deleteJob) =>
          parseJob.flatMap(s => scala.util.Try(s.toLong).toOption).foreach { linkId =>
            logger.info(s"Dequeued parse job for link $linkId")
            parser ! ParserWorker.Parse(linkId, context.self)
          }

          exportJob.flatMap(s => scala.util.Try(s.toLong).toOption).foreach { linkId =>
            logger.info(s"Dequeued re-export job for link $linkId")
            exporter ! ExporterWorker.ReExport(linkId, context.self)
          }

          deleteJob.flatMap(s => scala.util.Try(s.toLong).toOption).foreach { linkId =>
            logger.info(s"Dequeued delete job for link $linkId")
            exporter ! ExporterWorker.DeleteExport(linkId, context.self)
          }

          running(config, parser, exporter, redis, busy = false, shutdown)

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
          shutdown.unsafeRunSync()
          Behaviors.stopped
      }
    }
  }
}
