package repositories

import javax.inject._
import play.api.libs.json._
import cats.effect._
import cats.effect.unsafe.implicits.global
import cats.implicits._
import doobie._
import doobie.implicits._
import doobie.implicits.javasql._
import doobie.util.meta.Meta

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class LinkRepository @Inject()(
  transactor: Transactor[IO]
)(implicit ec: ExecutionContext) {

  implicit val instantMeta: Meta[Instant] =
    Meta[java.sql.Timestamp].imap(_.toInstant)(java.sql.Timestamp.from)

  /** Insert a new link and return its ID */
  def insert(
    url:             String,
    urlHash:         String,
    collectionId:    Option[Long],
    userId:          Long,
    exportDirectory: Option[String],
    exportFileName:  Option[String]
  ): Future[Long] = {
    sql"""INSERT INTO links (
            url,
            url_hash,
            collection_id,
            user_id,
            status,
            export_directory,
            export_file_name
          )
          VALUES (
            $url,
            $urlHash,
            $collectionId,
            $userId,
            'pending',
            $exportDirectory,
            $exportFileName
          )""".update
      .withUniqueGeneratedKeys[Long]("id")
      .transact(transactor)
      .unsafeToFuture()
  }

  /** Find a link by ID (optionally scoped to user) */
  def findById(id: Long, userId: Option[Long] = None): Future[Option[JsObject]] = {
    val baseQuery = userId match {
      case Some(uid) =>
        sql"""SELECT id, url, url_hash, title, content_md, reading_time_min,
                     status, collection_id, user_id, export_directory, export_file_name,
                     saved_at, parsed_at, indexed_at
              FROM links WHERE id = $id AND user_id = $uid"""
      case None =>
        sql"""SELECT id, url, url_hash, title, content_md, reading_time_min,
                     status, collection_id, user_id, export_directory, export_file_name,
                     saved_at, parsed_at, indexed_at
              FROM links WHERE id = $id"""
    }

    val query = for {
      linkOpt <- baseQuery
        .query[(Long, String, String, Option[String], Option[String], Option[Int],
                String, Option[Long], Long, Option[String], Option[String],
                Instant, Option[Instant], Option[Instant])]
        .option

      tags <- sql"""SELECT t.name FROM tags t
                    JOIN link_tags lt ON lt.tag_id = t.id
                    WHERE lt.link_id = $id"""
        .query[String]
        .to[List]
    } yield linkOpt.map {
      case (
            lid,
            url,
            hash,
            title,
            content,
            readTime,
            status,
            cid,
            uid,
            exportDirectory,
            exportFileName,
            saved,
            parsed,
            indexed
          ) =>
      Json.obj(
        "id"             -> lid,
        "url"            -> url,
        "urlHash"        -> hash,
        "title"          -> title,
        "contentMd"      -> content,
        "readingTimeMin" -> readTime,
        "status"         -> status,
        "collectionId"   -> cid,
        "userId"         -> uid,
        "exportDirectory" -> exportDirectory,
        "exportFileName"  -> exportFileName,
        "savedAt"        -> saved.toString,
        "parsedAt"       -> parsed.map(_.toString),
        "indexedAt"      -> indexed.map(_.toString),
        "tags"           -> tags
      )
    }

    query.transact(transactor).unsafeToFuture()
  }

  /** Find all links for a user with pagination */
  def findAllByUser(userId: Long, limit: Int = 20, offset: Int = 0): Future[List[JsObject]] = {
    val query = for {
      links <- sql"""SELECT id, url, url_hash, title, reading_time_min,
                            status, collection_id, export_directory, export_file_name,
                            saved_at, parsed_at
                     FROM links WHERE user_id = $userId
                     ORDER BY saved_at DESC
                     LIMIT $limit OFFSET $offset"""
        .query[(Long, String, String, Option[String], Option[Int],
                String, Option[Long], Option[String], Option[String], Instant, Option[Instant])]
        .to[List]

      results <- links.traverse {
        case (lid, url, hash, title, readTime, status, cid, exportDirectory, exportFileName, saved, parsed) =>
        sql"""SELECT t.name FROM tags t
              JOIN link_tags lt ON lt.tag_id = t.id
              WHERE lt.link_id = $lid"""
          .query[String]
          .to[List]
          .map { tags =>
            Json.obj(
              "id"             -> lid,
              "url"            -> url,
              "urlHash"        -> hash,
              "title"          -> title,
              "readingTimeMin" -> readTime,
              "status"         -> status,
              "collectionId"   -> cid,
              "exportDirectory" -> exportDirectory,
              "exportFileName"  -> exportFileName,
              "savedAt"        -> saved.toString,
              "parsedAt"       -> parsed.map(_.toString),
              "tags"           -> tags
            )
          }
      }
    } yield results

    query.transact(transactor).unsafeToFuture()
  }

  /** Count total links for a user */
  def countByUser(userId: Long): Future[Long] = {
    sql"SELECT COUNT(*) FROM links WHERE user_id = $userId"
      .query[Long]
      .unique
      .transact(transactor)
      .unsafeToFuture()
  }

  /** Attach tags to a link (creates tags if they don't exist) */
  def attachTags(linkId: Long, tagNames: List[String]): Future[Unit] = {
    val ops = tagNames.traverse_ { name =>
      for {
        // upsert tag
        _ <- sql"""INSERT IGNORE INTO tags (name) VALUES ($name)""".update.run
        tagId <- sql"SELECT id FROM tags WHERE name = $name".query[Long].unique
        _ <- sql"INSERT IGNORE INTO link_tags (link_id, tag_id) VALUES ($linkId, $tagId)".update.run
      } yield ()
    }
    ops.transact(transactor).unsafeToFuture()
  }

  /** Remove all tags from a link */
  def detachAllTags(linkId: Long): Future[Unit] = {
    sql"DELETE FROM link_tags WHERE link_id = $linkId"
      .update.run
      .transact(transactor)
      .void
      .unsafeToFuture()
  }

  /** Update link metadata (collectionId, tags) — user-scoped */
  def update(
    id:              Long,
    userId:          Long,
    collectionId:    Option[Long],
    tags:            List[String],
    exportDirectory: Option[String],
    exportFileName:  Option[String]
  ): Future[Boolean] = {
    val ops = for {
      updated <- sql"""UPDATE links
                       SET collection_id = $collectionId,
                           export_directory = $exportDirectory,
                           export_file_name = $exportFileName
                       WHERE id = $id AND user_id = $userId""".update.run
      _ <- if (updated > 0) {
        sql"DELETE FROM link_tags WHERE link_id = $id".update.run *>
          tags.traverse_ { name =>
            for {
              _ <- sql"INSERT IGNORE INTO tags (name) VALUES ($name)".update.run
              tagId <- sql"SELECT id FROM tags WHERE name = $name".query[Long].unique
              _ <- sql"INSERT IGNORE INTO link_tags (link_id, tag_id) VALUES ($id, $tagId)".update.run
            } yield ()
          }
      } else {
        FC.pure(())
      }
    } yield updated > 0

    ops.transact(transactor).unsafeToFuture()
  }

  /** Delete a link — user-scoped */
  def delete(id: Long, userId: Long): Future[Boolean] = {
    sql"DELETE FROM links WHERE id = $id AND user_id = $userId"
      .update.run
      .map(_ > 0)
      .transact(transactor)
      .unsafeToFuture()
  }

  /** Update link after parsing */
  def updateParsed(id: Long, title: String, contentMd: String, readingTimeMin: Int): Future[Unit] = {
    sql"""UPDATE links
          SET title = $title, content_md = $contentMd, reading_time_min = $readingTimeMin,
              status = 'parsed', parsed_at = NOW()
          WHERE id = $id"""
      .update.run
      .transact(transactor)
      .void
      .unsafeToFuture()
  }

  /** Mark link as indexed in ES */
  def markIndexed(id: Long): Future[Unit] = {
    sql"UPDATE links SET indexed_at = NOW() WHERE id = $id"
      .update.run
      .transact(transactor)
      .void
      .unsafeToFuture()
  }

  /** Mark link as failed */
  def markFailed(id: Long): Future[Unit] = {
    sql"UPDATE links SET status = 'failed' WHERE id = $id"
      .update.run
      .transact(transactor)
      .void
      .unsafeToFuture()
  }

  /** Find not-yet-indexed links for ES resync */
  def findNotIndexed(limit: Int = 100): Future[List[(Long, String, String)]] = {
    sql"""SELECT id, url, COALESCE(content_md, '') FROM links
          WHERE status = 'parsed' AND indexed_at IS NULL
          LIMIT $limit"""
      .query[(Long, String, String)]
      .to[List]
      .transact(transactor)
      .unsafeToFuture()
  }
}
