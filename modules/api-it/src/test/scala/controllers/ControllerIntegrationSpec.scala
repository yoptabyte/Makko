package controllers

import actions.{AuthAction, AuthRequest}
import auth.AuthUser
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.dimafeng.testcontainers.MySQLContainer
import doobie.Transactor
import doobie.hikari.HikariTransactor
import doobie.implicits._
import io.markko.shared.domain.User
import org.scalatest.CancelAfterFailure
import org.scalatestplus.play.PlaySpec
import org.testcontainers.DockerClientFactory
import play.api.Configuration
import play.api.libs.json.Json
import play.api.mvc._
import play.api.test.Helpers._
import play.api.test._
import play.silhouette.impl.authenticators.JWTAuthenticator
import repositories.{CollectionRepository, ExportJobRepository, LinkRepository}
import services.{ElasticsearchService, RedisService}
import modules.DatabaseInitializer

import java.security.MessageDigest
import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class ControllerIntegrationSpec extends PlaySpec with Results with CancelAfterFailure {

  implicit val ec: ExecutionContext = ExecutionContext.global

  "CollectionController and ExportController" must {
    "work against a migrated MySQL database" in {
      dockerAvailability() match {
        case Left(reason) =>
          cancel(s"Docker/Testcontainers unavailable for integration test: $reason")
        case Right(()) =>
          withMysqlContainer { mysql =>
            val config = Configuration.from(Map(
              "markko.database.url" -> mysql.jdbcUrl,
              "markko.database.user" -> mysql.username,
              "markko.database.password" -> mysql.password,
              "markko.database.pool-size" -> 1,
              "markko.redis.host" -> "localhost",
              "markko.redis.port" -> 6379,
              "markko.elasticsearch.host" -> "localhost",
              "markko.elasticsearch.port" -> 9200,
              "markko.elasticsearch.scheme" -> "http",
              "markko.elasticsearch.username" -> "elastic",
              "markko.elasticsearch.password" -> "elastic"
            ))

            new DatabaseInitializer(config, new StubElasticsearchService)

            val transactor = HikariTransactor.newHikariTransactor[IO](
              "com.mysql.cj.jdbc.Driver",
              mysql.jdbcUrl,
              mysql.username,
              mysql.password,
              ExecutionContext.global
            ).allocated.unsafeRunSync()._1

            val primaryUserId = insertUser(transactor, "integration@example.com", "Integration User")
            val secondaryUserId = insertUser(transactor, "other@example.com", "Other User")

            val primaryUser = authUser(primaryUserId, "integration@example.com", "Integration User")
            val secondaryUser = authUser(secondaryUserId, "other@example.com", "Other User")

            val collectionRepo = new CollectionRepository(transactor)
            val linkRepo = new LinkRepository(transactor)
            val exportRepo = new ExportJobRepository(transactor)
            val redisService = new StubRedisService(config)

            val collectionController =
              new CollectionController(stubControllerComponents(), collectionRepo, new FakeAuthAction(primaryUser))

            val createCollectionRequest = FakeRequest(POST, "/collections")
              .withBody(Json.obj("name" -> "Integration Shelf"))
              .withHeaders(CONTENT_TYPE -> "application/json")

            val createCollectionResult = collectionController.create().apply(createCollectionRequest)
            status(createCollectionResult) mustBe CREATED

            val collectionId = (contentAsJson(createCollectionResult) \ "id").as[Long]
            (contentAsJson(createCollectionResult) \ "slug").as[String] mustBe "integration-shelf"

            val otherCollectionController =
              new CollectionController(stubControllerComponents(), collectionRepo, new FakeAuthAction(secondaryUser))

            val otherCollectionRequest = FakeRequest(POST, "/collections")
              .withBody(Json.obj("name" -> "Other Shelf"))
              .withHeaders(CONTENT_TYPE -> "application/json")

            status(otherCollectionController.create().apply(otherCollectionRequest)) mustBe CREATED

            val listCollectionsResult = collectionController.list().apply(FakeRequest(GET, "/collections"))
            status(listCollectionsResult) mustBe OK
            val collections = contentAsJson(listCollectionsResult).as[play.api.libs.json.JsArray].value
            collections must have size 1
            (collections.head \ "name").as[String] mustBe "Integration Shelf"

            val linkId = await(linkRepo.insert(
              "https://example.com/integration",
              md5("https://example.com/integration"),
              Some(collectionId),
              primaryUserId
            ))
            await(linkRepo.attachTags(linkId, List("scala", "integration")))
            await(linkRepo.updateParsed(linkId, "Integration Link", "Parsed markdown body", 5))
            val exportJobId = await(exportRepo.insert(linkId))
            await(exportRepo.updateStatus(exportJobId, "done", Some("vault/collections/integration-link.md")))

            val exportController = new ExportController(
              stubControllerComponents(),
              linkRepo,
              exportRepo,
              redisService,
              new FakeAuthAction(primaryUser)
            )

            val downloadResult = exportController.download(linkId).apply(FakeRequest(GET, s"/export/$linkId"))
            status(downloadResult) mustBe OK
            contentAsString(downloadResult) must include("# Integration Link")
            contentAsString(downloadResult) must include("Parsed markdown body")

            val statusResult = exportController.status(linkId).apply(FakeRequest(GET, s"/export/$linkId/status"))
            status(statusResult) mustBe OK
            (contentAsJson(statusResult) \ 0 \ "status").as[String] mustBe "done"

            val reexportResult = exportController.reexport(linkId).apply(FakeRequest(POST, s"/export/$linkId/reexport"))
            status(reexportResult) mustBe ACCEPTED
            redisService.enqueuedExportIds must contain(linkId)

            val deleteResult = exportController.deleteExport(linkId).apply(FakeRequest(DELETE, s"/export/$linkId"))
            status(deleteResult) mustBe OK
            redisService.enqueuedDeleteIds must contain(linkId)
            (contentAsJson(deleteResult) \ "vaultPath").as[String] mustBe "vault/collections/integration-link.md"
          }
      }
    }
  }

  private def authUser(id: Long, email: String, name: String): AuthUser =
    AuthUser(
      User(
        id = Some(id),
        email = email,
        name = name,
        password = None,
        createdAt = Some(LocalDateTime.now()),
        updatedAt = Some(LocalDateTime.now())
      )
    )

  private def insertUser(transactor: Transactor[IO], email: String, name: String): Long =
    sql"INSERT INTO users (email, name, password_hash) VALUES ($email, $name, 'hashed-password')"
      .update
      .withUniqueGeneratedKeys[Long]("id")
      .transact(transactor)
      .unsafeRunSync()

  private def md5(value: String): String = {
    val digest = MessageDigest.getInstance("MD5")
    digest.digest(value.getBytes("UTF-8")).map("%02x".format(_)).mkString
  }

  private def dockerAvailability(): Either[String, Unit] =
    Try {
      DockerClientFactory.instance().client()
      ()
    }.toEither.left.map(_.getMessage)

  private def withMysqlContainer[A](f: MySQLContainer => A): A = {
    val mysql = MySQLContainer(
      databaseName = "markko",
      username = "markko",
      password = "test-mysql-password"
    )
    mysql.start()
    try f(mysql)
    finally mysql.stop()
  }

  private class FakeAuthAction(user: AuthUser) extends AuthAction(null, null, null, null, null)(ec) {
    override def invokeBlock[A](request: Request[A], block: AuthRequest[A] => Future[Result]): Future[Result] = {
      val fakeAuthenticator = null.asInstanceOf[JWTAuthenticator]
      block(AuthRequest("fake-token", user, fakeAuthenticator, request))
    }
  }

  private class StubRedisService(config: Configuration) extends RedisService(config, null)(ec) {
    var enqueuedExportIds = Vector.empty[Long]
    var enqueuedDeleteIds = Vector.empty[Long]

    override def enqueueExportJob(linkId: Long): Future[Unit] = Future.successful {
      enqueuedExportIds = enqueuedExportIds :+ linkId
    }

    override def enqueueDeleteJob(linkId: Long): Future[Unit] = Future.successful {
      enqueuedDeleteIds = enqueuedDeleteIds :+ linkId
    }
  }

  private class StubElasticsearchService extends ElasticsearchService(
    Configuration.from(Map(
      "markko.elasticsearch.host" -> "localhost",
      "markko.elasticsearch.port" -> 9200,
      "markko.elasticsearch.scheme" -> "http",
      "markko.elasticsearch.username" -> "elastic",
      "markko.elasticsearch.password" -> "elastic"
    ))
  ) {
    override def ensureIndex(): Future[Unit] = Future.successful(())
  }
}
