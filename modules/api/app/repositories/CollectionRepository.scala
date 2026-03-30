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
class CollectionRepository @Inject()(
  transactor: Transactor[IO]
)(implicit ec: ExecutionContext) {

  implicit val instantMeta: Meta[Instant] =
    Meta[java.sql.Timestamp].imap(_.toInstant)(java.sql.Timestamp.from)

  /** Insert a new collection and return its ID */
  def insert(name: String, slug: String, userId: Long): Future[Long] = {
    sql"INSERT INTO collections (name, slug, user_id) VALUES ($name, $slug, $userId)"
      .update
      .withUniqueGeneratedKeys[Long]("id")
      .transact(transactor)
      .unsafeToFuture()
  }

  /** Find all collections for a user */
  def findAllByUser(userId: Long): Future[List[JsObject]] = {
    sql"""SELECT c.id, c.name, c.slug, c.created_at, COUNT(l.id) as link_count
          FROM collections c
          LEFT JOIN links l ON l.collection_id = c.id
          WHERE c.user_id = $userId
          GROUP BY c.id
          ORDER BY c.name"""
      .query[(Long, String, String, Instant, Long)]
      .to[List]
      .map(_.map { case (id, name, slug, createdAt, linkCount) =>
        Json.obj(
          "id"        -> id,
          "name"      -> name,
          "slug"      -> slug,
          "createdAt" -> createdAt.toString,
          "linkCount" -> linkCount
        )
      })
      .transact(transactor)
      .unsafeToFuture()
  }

  /** Find collection by ID (user-scoped) */
  def findById(id: Long, userId: Long): Future[Option[JsObject]] = {
    sql"SELECT id, name, slug, created_at FROM collections WHERE id = $id AND user_id = $userId"
      .query[(Long, String, String, Instant)]
      .option
      .map(_.map { case (cid, name, slug, createdAt) =>
        Json.obj("id" -> cid, "name" -> name, "slug" -> slug, "createdAt" -> createdAt.toString)
      })
      .transact(transactor)
      .unsafeToFuture()
  }

  /** Update a collection — user-scoped */
  def update(id: Long, userId: Long, name: String, slug: String): Future[Boolean] = {
    sql"UPDATE collections SET name = $name, slug = $slug WHERE id = $id AND user_id = $userId"
      .update.run
      .map(_ > 0)
      .transact(transactor)
      .unsafeToFuture()
  }

  /** Delete a collection — user-scoped. Orphaned links keep collection_id = NULL */
  def delete(id: Long, userId: Long): Future[Boolean] = {
    val ops = for {
      _       <- sql"UPDATE links SET collection_id = NULL WHERE collection_id = $id AND user_id = $userId".update.run
      deleted <- sql"DELETE FROM collections WHERE id = $id AND user_id = $userId".update.run
    } yield deleted > 0

    ops.transact(transactor).unsafeToFuture()
  }
}
