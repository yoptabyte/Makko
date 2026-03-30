package repositories

import javax.inject._
import play.api.libs.json._
import cats.effect._
import cats.effect.unsafe.implicits.global
import doobie._
import doobie.implicits._
import doobie.implicits.javasql._
import doobie.util.meta.Meta

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ExportJobRepository @Inject()(
  transactor: Transactor[IO]
)(implicit ec: ExecutionContext) {

  implicit val instantMeta: Meta[Instant] =
    Meta[java.sql.Timestamp].imap(_.toInstant)(java.sql.Timestamp.from)

  /** Create a new export job */
  def insert(linkId: Long): Future[Long] = {
    val op = for {
      _ <- sql"""
        INSERT INTO export_jobs (link_id, status, vault_path, error_msg, completed_at)
        VALUES ($linkId, 'pending', NULL, NULL, NULL)
        ON DUPLICATE KEY UPDATE
          status = 'pending',
          error_msg = NULL,
          completed_at = NULL
      """.update.run
      id <- sql"SELECT id FROM export_jobs WHERE link_id = $linkId"
        .query[Long]
        .unique
    } yield id

    op.transact(transactor).unsafeToFuture()
  }

  /** Find export jobs by link ID */
  def findByLinkId(linkId: Long): Future[List[JsObject]] = {
    sql"""SELECT id, link_id, status, vault_path, error_msg, created_at, completed_at
          FROM export_jobs WHERE link_id = $linkId ORDER BY created_at DESC"""
      .query[(Long, Long, String, Option[String], Option[String], Instant, Option[Instant])]
      .to[List]
      .map(_.map { case (id, lid, status, vPath, err, created, completed) =>
        Json.obj(
          "id"          -> id,
          "linkId"      -> lid,
          "status"      -> status,
          "vaultPath"   -> vPath,
          "errorMsg"    -> err,
          "createdAt"   -> created.toString,
          "completedAt" -> completed.map(_.toString)
        )
      })
      .transact(transactor)
      .unsafeToFuture()
  }

  /** Update status of an export job */
  def updateStatus(id: Long, status: String, vaultPath: Option[String] = None, errorMsg: Option[String] = None): Future[Unit] = {
    val update =
      if (status == "done" || status == "failed") {
        sql"""UPDATE export_jobs
              SET status = $status, vault_path = $vaultPath, error_msg = $errorMsg,
                  completed_at = NOW()
              WHERE id = $id""".update.run
      } else {
        sql"""UPDATE export_jobs
              SET status = $status, vault_path = $vaultPath, error_msg = $errorMsg,
                  completed_at = NULL
              WHERE id = $id""".update.run
      }

    update.transact(transactor).void.unsafeToFuture()
  }

  /** Find the latest successful export for a link */
  def findLatestSuccess(linkId: Long): Future[Option[JsObject]] = {
    sql"""SELECT id, link_id, status, vault_path, created_at, completed_at
          FROM export_jobs
          WHERE link_id = $linkId AND status = 'done'
          ORDER BY completed_at DESC LIMIT 1"""
      .query[(Long, Long, String, Option[String], Instant, Option[Instant])]
      .option
      .map(_.map { case (id, lid, status, vPath, created, completed) =>
        Json.obj(
          "id"          -> id,
          "linkId"      -> lid,
          "status"      -> status,
          "vaultPath"   -> vPath,
          "createdAt"   -> created.toString,
          "completedAt" -> completed.map(_.toString)
        )
      })
      .transact(transactor)
      .unsafeToFuture()
  }

  /** Find the latest known vault path for a link, even if a re-export is currently pending */
  def findLatestExportedPath(linkId: Long): Future[Option[JsObject]] = {
    sql"""SELECT id, link_id, status, vault_path, created_at, completed_at
          FROM export_jobs
          WHERE link_id = $linkId AND vault_path IS NOT NULL
          ORDER BY COALESCE(completed_at, created_at) DESC LIMIT 1"""
      .query[(Long, Long, String, Option[String], Instant, Option[Instant])]
      .option
      .map(_.map { case (id, lid, status, vPath, created, completed) =>
        Json.obj(
          "id"          -> id,
          "linkId"      -> lid,
          "status"      -> status,
          "vaultPath"   -> vPath,
          "createdAt"   -> created.toString,
          "completedAt" -> completed.map(_.toString)
        )
      })
      .transact(transactor)
      .unsafeToFuture()
  }

  /** List all pending export jobs */
  def findPending(limit: Int = 50): Future[List[(Long, Long)]] = {
    sql"SELECT id, link_id FROM export_jobs WHERE status = 'pending' ORDER BY created_at LIMIT $limit"
      .query[(Long, Long)]
      .to[List]
      .transact(transactor)
      .unsafeToFuture()
  }
}
