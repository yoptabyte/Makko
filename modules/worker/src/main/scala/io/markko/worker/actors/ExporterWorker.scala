package io.markko.worker.actors

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import io.markko.worker.services.{ObsidianSync, TagIndexGenerator, WikilinkBuilder}

import cats.effect._
import cats.effect.unsafe.implicits.global
import doobie._
import doobie.implicits._
import doobie.implicits.javasql._
import doobie.hikari.HikariTransactor
import doobie.util.meta.Meta

import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.util.{Try, Success, Failure}

/**
 * Dedicated export actor: takes a parsed link and writes it to the Obsidian vault.
 * Separated from ParserWorker for clean separation of concerns.
 */
object ExporterWorker extends LazyLogging {

  sealed trait Command
  case class Export(linkId: Long, replyTo: ActorRef[ParserSupervisor.Command]) extends Command
  case class ReExport(linkId: Long, replyTo: ActorRef[ParserSupervisor.Command]) extends Command
  case class DeleteExport(linkId: Long, replyTo: ActorRef[ParserSupervisor.Command]) extends Command

  implicit val instantMeta: Meta[Instant] =
    Meta[java.sql.Timestamp].imap(_.toInstant)(java.sql.Timestamp.from)

  def apply(config: Config): Behavior[Command] = {
    Behaviors.setup { context =>
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

      active(transactor, obsidianSync, tagIndexGen, wikilinkBuilder, config)
    }
  }

  private def active(
    xa:              Transactor[IO],
    obsidianSync:    ObsidianSync,
    tagIndexGen:     TagIndexGenerator,
    wikilinkBuilder: WikilinkBuilder,
    config:          Config
  ): Behavior[Command] = {
    Behaviors.receive { (context, message) =>
      message match {

        case Export(linkId, replyTo) =>
          logger.info(s"Starting export for link $linkId")
          doExport(linkId, xa, obsidianSync, tagIndexGen, wikilinkBuilder)
          replyTo ! ParserSupervisor.ExportComplete(linkId, success = true)
          Behaviors.same

        case ReExport(linkId, replyTo) =>
          logger.info(s"Re-exporting link $linkId")
          // Delete old note first, then re-export
          deleteFromVault(linkId, xa, obsidianSync)
          doExport(linkId, xa, obsidianSync, tagIndexGen, wikilinkBuilder)
          replyTo ! ParserSupervisor.ExportComplete(linkId, success = true)
          Behaviors.same

        case DeleteExport(linkId, replyTo) =>
          logger.info(s"Deleting export for link $linkId")
          deleteFromVault(linkId, xa, obsidianSync)
          // Update export_jobs status
          sql"""UPDATE export_jobs SET status = 'failed', error_msg = 'Deleted by user', completed_at = NOW()
                WHERE link_id = $linkId AND status = 'done'"""
            .update.run.transact(xa).unsafeRunSync()
          replyTo ! ParserSupervisor.ExportComplete(linkId, success = true)
          Behaviors.same
      }
    }
  }

  /** Core export pipeline: load link → build note → write to vault → update DB */
  private def doExport(
    linkId:          Long,
    xa:              Transactor[IO],
    obsidianSync:    ObsidianSync,
    tagIndexGen:     TagIndexGenerator,
    wikilinkBuilder: WikilinkBuilder
  ): Unit = {
    Try {
      // Create export job record
      sql"""INSERT INTO export_jobs (link_id, status) VALUES ($linkId, 'exporting')
            ON DUPLICATE KEY UPDATE status = 'exporting', completed_at = NULL"""
        .update.run.transact(xa).unsafeRunSync()

      // Load link data
      val (url, title, contentMd, readingTime, collectionSlug, exportDirectory, exportFileName) = sql"""
        SELECT l.url, COALESCE(l.title, ''), COALESCE(l.content_md, ''),
               COALESCE(l.reading_time_min, 1), COALESCE(c.slug, 'unsorted'),
               l.export_directory, l.export_file_name
        FROM links l
        LEFT JOIN collections c ON c.id = l.collection_id
        WHERE l.id = $linkId AND l.status = 'parsed'
      """.query[(String, String, String, Int, String, Option[String], Option[String])]
        .unique.transact(xa).unsafeRunSync()

      // Load tags
      val tags = sql"""SELECT t.name FROM tags t
                       JOIN link_tags lt ON lt.tag_id = t.id
                       WHERE lt.link_id = $linkId"""
        .query[String].to[List].transact(xa).unsafeRunSync()

      // Build related wikilinks
      val relatedLinks = wikilinkBuilder.findRelated(linkId)

      // Load image mappings (stored as JSON in a simple format)
      // For now, images are already in the assets dir from ParserWorker
      val images = Map.empty[String, String] // images were already placed by downloader

      // Write the Obsidian note
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

      // Rebuild tag indexes
      tagIndexGen.rebuildAll()

      // Update export_jobs
      val vaultPathStr = notePath.toString
      sql"""UPDATE export_jobs SET status = 'done', vault_path = $vaultPathStr, completed_at = NOW()
            WHERE link_id = $linkId AND status = 'exporting'"""
        .update.run.transact(xa).unsafeRunSync()

      logger.info(s"Export complete for link $linkId → $notePath")

    } match {
      case Success(_) => ()
      case Failure(ex) =>
        logger.error(s"Export failed for link $linkId", ex)
        val errMsg = ex.getMessage
        sql"""UPDATE export_jobs SET status = 'failed', error_msg = $errMsg, completed_at = NOW()
              WHERE link_id = $linkId"""
          .update.run.transact(xa).unsafeRunSync()
    }
  }

  /** Remove a note from the vault */
  private def deleteFromVault(linkId: Long, xa: Transactor[IO], obsidianSync: ObsidianSync): Unit = {
    // Look up the vault path from export_jobs
    val pathOpt = sql"SELECT vault_path FROM export_jobs WHERE link_id = $linkId AND status = 'done'"
      .query[Option[String]].option.transact(xa).unsafeRunSync().flatten

    pathOpt.foreach { path =>
      obsidianSync.deleteNote(path)
    }
  }
}
