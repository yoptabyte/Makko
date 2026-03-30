package io.markko.worker.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import io.markko.worker.services.{ElasticsearchResync, WikilinkBuilder, ObsidianSync}

import cats.effect._
import cats.effect.unsafe.implicits.global
import doobie._
import doobie.hikari.HikariTransactor

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
 * Periodic actor that handles:
 * - ES resync (index unindexed links every 30s)
 * - Backlinks graph rebuild (every 5min)
 * - ES lag metric update (every 15s)
 */
object ResyncActor extends LazyLogging {

  sealed trait Command
  case object RunResync extends Command
  case object RebuildGraph extends Command
  case object UpdateMetrics extends Command

  private case object ResyncTimerKey
  private case object GraphTimerKey
  private case object MetricsTimerKey

  def apply(config: Config): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        val dbUrl      = config.getString("markko.database.url")
        val dbUser     = config.getString("markko.database.user")
        val dbPassword = config.getString("markko.database.password")
        val vaultPath  = config.getString("markko.vault.base-path")

        val xa = HikariTransactor.newHikariTransactor[IO](
          driverClassName = "com.mysql.cj.jdbc.Driver",
          url             = dbUrl,
          user            = dbUser,
          pass            = dbPassword,
          connectEC       = ExecutionContext.global
        ).allocated.unsafeRunSync()._1

        val esResync        = new ElasticsearchResync(config, xa)
        val wikilinkBuilder = new WikilinkBuilder(xa)
        val obsidianSync    = new ObsidianSync(vaultPath)

        // Schedule periodic tasks
        timers.startTimerWithFixedDelay(ResyncTimerKey, RunResync, 30.seconds)
        timers.startTimerWithFixedDelay(GraphTimerKey, RebuildGraph, 5.minutes)
        timers.startTimerWithFixedDelay(MetricsTimerKey, UpdateMetrics, 15.seconds)

        logger.info("ResyncActor started: ES resync every 30s, graph rebuild every 5m, metrics every 15s")

        active(esResync, wikilinkBuilder, obsidianSync)
      }
    }
  }

  private def active(
    esResync:        ElasticsearchResync,
    wikilinkBuilder: WikilinkBuilder,
    obsidianSync:    ObsidianSync
  ): Behavior[Command] = {
    Behaviors.receive { (_, message) =>
      message match {
        case RunResync =>
          try {
            val count = esResync.syncUnindexed()
            if (count > 0) logger.info(s"Resync: indexed $count documents")
          } catch {
            case ex: Exception =>
              logger.error("ES resync failed", ex)
          }
          Behaviors.same

        case RebuildGraph =>
          try {
            val backlinks = wikilinkBuilder.buildBacklinksMap()
            obsidianSync.rebuildIndex(backlinks)
            logger.info(s"Rebuilt backlinks graph with ${backlinks.size} entries")
          } catch {
            case ex: Exception =>
              logger.error("Graph rebuild failed", ex)
          }
          Behaviors.same

        case UpdateMetrics =>
          try {
            esResync.updateLagMetric()
          } catch {
            case _: Exception => // silent
          }
          Behaviors.same
      }
    }
  }
}
