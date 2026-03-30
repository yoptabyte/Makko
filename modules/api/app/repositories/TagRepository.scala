package repositories

import javax.inject._
import play.api.libs.json._
import cats.effect._
import cats.effect.unsafe.implicits.global
import doobie._
import doobie.implicits._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class TagRepository @Inject()(
  transactor: Transactor[IO]
)(implicit ec: ExecutionContext) {

  /** Find or create a tag, returning its ID */
  def findOrCreate(name: String): Future[Long] = {
    (for {
      _ <- sql"INSERT IGNORE INTO tags (name) VALUES ($name)".update.run
      id <- sql"SELECT id FROM tags WHERE name = $name".query[Long].unique
    } yield id).transact(transactor).unsafeToFuture()
  }

  /** Get all tags for a given link */
  def findByLinkId(linkId: Long): Future[List[JsObject]] = {
    sql"""SELECT t.id, t.name FROM tags t
          JOIN link_tags lt ON lt.tag_id = t.id
          WHERE lt.link_id = $linkId"""
      .query[(Long, String)]
      .to[List]
      .map(_.map { case (id, name) => Json.obj("id" -> id, "name" -> name) })
      .transact(transactor)
      .unsafeToFuture()
  }

  /** List all tags with link count */
  def listAll(): Future[List[JsObject]] = {
    sql"""SELECT t.id, t.name, COUNT(lt.link_id) as link_count
          FROM tags t
          LEFT JOIN link_tags lt ON lt.tag_id = t.id
          GROUP BY t.id
          ORDER BY link_count DESC"""
      .query[(Long, String, Long)]
      .to[List]
      .map(_.map { case (id, name, count) =>
        Json.obj("id" -> id, "name" -> name, "linkCount" -> count)
      })
      .transact(transactor)
      .unsafeToFuture()
  }
}
