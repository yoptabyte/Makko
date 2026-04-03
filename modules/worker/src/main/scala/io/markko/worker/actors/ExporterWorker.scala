package io.markko.worker.actors

import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import io.markko.worker.services.{ObsidianSync, TagIndexGenerator, WikilinkBuilder, WorkerMetrics}

import cats.effect._
import cats.effect.unsafe.implicits.global
import doobie._
import doobie.implicits._
import doobie.implicits.javasql._
import doobie.hikari.HikariTransactor
import doobie.util.meta.Meta

import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}

object ExporterWorker extends LazyLogging {

  sealed trait Command
  case class Export(linkId: Long, replyTo: ActorRef[ParserSupervisor.Command]) extends Command
  case class ReExport(linkId: Long, replyTo: ActorRef[ParserSupervisor.Command]) extends Command
  case class DeleteExport(linkId: Long, replyTo: ActorRef[ParserSupervisor.Command]) extends Command
  private case class ExportResult(linkId: Long, success: Boolean, replyTo: ActorRef[ParserSupervisor.Command]) extends Command

  implicit val instantMeta: Meta[Instant] =
    Meta[java.sql.Timestamp].imap(_.toInstant)(java.sql.Timestamp.from)

  def apply(config: Config): Behavior[Command] = {
    Behaviors.supervise[Command] {
      Behaviors.setup[Command] { context =>
        val dbUrl      = config.getString("markko.database.url")
        val dbUser     = config.getString("markko.database.user")
        val dbPassword = config.getString("markko.database.password")
        val vaultPath  = config.getString("markko.vault.base-path")
        val allowAbsoluteExportPaths =
          if (config.hasPath("markko.vault.allow-absolute-export-paths")) {
            config.getBoolean("markko.vault.allow-absolute-export-paths")
          } else {
            false
          }

        val transactor = HikariTransactor.newHikariTransactor[IO](
          driverClassName = "com.mysql.cj.jdbc.Driver",
          url             = dbUrl,
          user            = dbUser,
          pass            = dbPassword,
          connectEC       = ExecutionContext.global
        ).allocated.unsafeRunSync()._1

        val obsidianSync    = new ObsidianSync(vaultPath, allowAbsoluteExportPaths)
        val tagIndexGen     = new TagIndexGenerator(vaultPath, transactor)
        val wikilinkBuilder = new WikilinkBuilder(transactor)

        context.log.info("ExporterWorker initialized")

        active(transactor, obsidianSync, tagIndexGen, wikilinkBuilder)
      }
    }.onFailure[Exception](SupervisorStrategy.restartWithBackoff(1.second, 30.seconds, 0.2))
  }

  private def active(
    xa:              Transactor[IO],
    obsidianSync:    ObsidianSync,
    tagIndexGen:     TagIndexGenerator,
    wikilinkBuilder: WikilinkBuilder
  ): Behavior[Command] = {
    Behaviors.receive { (context, message) =>
      implicit val ec: ExecutionContext = context.executionContext
      message match {

        case Export(linkId, replyTo) =>
          logger.info(s"Starting export for link $linkId")
          WorkerMetrics.exportJobsStarted.inc()
          context.pipeToSelf(scala.concurrent.Future {
            doExport(linkId, xa, obsidianSync, tagIndexGen, wikilinkBuilder)
          }) {
            case Success(success) => ExportResult(linkId, success, replyTo)
            case Failure(ex) =>
              logger.error(s"Export pipeline crashed for link $linkId", ex)
              ExportResult(linkId, success = false, replyTo)
          }
          Behaviors.same

        case ReExport(linkId, replyTo) =>
          logger.info(s"Re-exporting link $linkId")
          WorkerMetrics.exportJobsStarted.inc()
          context.pipeToSelf(scala.concurrent.Future {
            deleteFromVault(linkId, xa, obsidianSync)
            doExport(linkId, xa, obsidianSync, tagIndexGen, wikilinkBuilder)
          }) {
            case Success(success) => ExportResult(linkId, success, replyTo)
            case Failure(ex) =>
              logger.error(s"Re-export pipeline crashed for link $linkId", ex)
              ExportResult(linkId, success = false, replyTo)
          }
          Behaviors.same

        case DeleteExport(linkId, replyTo) =>
          logger.info(s"Deleting export for link $linkId")
          context.pipeToSelf(scala.concurrent.Future {
            deleteFromVault(linkId, xa, obsidianSync)
            sql"""UPDATE export_jobs SET status = 'deleted', error_msg = 'Deleted by user', completed_at = NOW()
                  WHERE link_id = $linkId AND status = 'done'"""
              .update.run.transact(xa).unsafeRunSync()
            true
          }) {
            case Success(_) => ExportResult(linkId, success = true, replyTo)
            case Failure(ex) =>
              logger.error(s"Delete export crashed for link $linkId", ex)
              ExportResult(linkId, success = false, replyTo)
          }
          Behaviors.same

        case ExportResult(linkId, success, replyTo) =>
          if (success) {
            logger.info(s"Export complete for link $linkId")
            WorkerMetrics.exportJobsCompleted.inc()
          } else {
            logger.warn(s"Export failed for link $linkId")
            WorkerMetrics.exportJobsFailed.inc()
          }
          replyTo ! ParserSupervisor.ExportComplete(linkId, success)
          Behaviors.same
      }
    }
  }

  private def doExport(
    linkId:          Long,
    xa:              Transactor[IO],
    obsidianSync:    ObsidianSync,
    tagIndexGen:     TagIndexGenerator,
    wikilinkBuilder: WikilinkBuilder
  ): Boolean = {
    sql"""INSERT INTO export_jobs (link_id, status) VALUES ($linkId, 'exporting')
          ON DUPLICATE KEY UPDATE status = 'exporting', completed_at = NULL"""
      .update.run.transact(xa).unsafeRunSync()

    val (url, title, contentMd, readingTime, collectionSlug, exportDirectory, exportFileName) = sql"""
      SELECT l.url, COALESCE(l.title, ''), COALESCE(l.content_md, ''),
             COALESCE(l.reading_time_min, 1), COALESCE(c.slug, 'unsorted'),
             l.export_directory, l.export_file_name
      FROM links l
      LEFT JOIN collections c ON c.id = l.collection_id
      WHERE l.id = $linkId AND l.status = 'parsed'
    """.query[(String, String, String, Int, String, Option[String], Option[String])]
      .unique.transact(xa).unsafeRunSync()

    val tags = sql"""SELECT t.name FROM tags t
                     JOIN link_tags lt ON lt.tag_id = t.id
                     WHERE lt.link_id = $linkId"""
      .query[String].to[List].transact(xa).unsafeRunSync()

    val relatedLinks = wikilinkBuilder.findRelated(linkId)
    val images = Map.empty[String, String]

    val notePath = obsidianSync.writeNote(
      title        = title,
      url          = url,
      tags         = tags,
      collection   = collectionSlug,
      exportDirectory = exportDirectory,
      exportFileName  = exportFileName,
      contentMd    = contentMd,
      readingTime  = readingTime,
      relatedLinks = relatedLinks,
      images       = images,
      linkId       = linkId
    )

    tagIndexGen.rebuildAll()

    val vaultPathStr = notePath.toString
    sql"""UPDATE export_jobs SET status = 'done', vault_path = $vaultPathStr, completed_at = NOW()
          WHERE link_id = $linkId AND status = 'exporting'"""
      .update.run.transact(xa).unsafeRunSync()

    logger.info(s"Export complete for link $linkId -> $notePath")
    true
  }

  private def deleteFromVault(linkId: Long, xa: Transactor[IO], obsidianSync: ObsidianSync): Unit = {
    val pathOpt = sql"SELECT vault_path FROM export_jobs WHERE link_id = $linkId AND status = 'done'"
      .query[Option[String]].option.transact(xa).unsafeRunSync().flatten

    pathOpt.foreach { path =>
      obsidianSync.deleteNote(path)
    }
  }
}
