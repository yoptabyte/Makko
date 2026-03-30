package controllers

import actions.{AuthAction, AuthRequest}
import auth.AuthUser
import io.markko.shared.domain.User
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{Materializer, SystemMaterializer}
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.libs.json._
import play.api.mvc._
import play.api.test.Helpers._
import play.api.test._
import play.silhouette.impl.authenticators.JWTAuthenticator
import repositories.{ExportJobRepository, LinkRepository}
import services.RedisService

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

class ExportControllerSpec extends PlaySpec with Results with BeforeAndAfterAll {

  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit lazy val actorSystem: ActorSystem = ActorSystem("export-controller-spec")
  implicit lazy val mat: Materializer = SystemMaterializer(actorSystem).materializer

  private val testConfig = Configuration.from(Map(
    "markko.redis.host" -> "localhost",
    "markko.redis.port" -> 6379
  ))

  val testUser: AuthUser = AuthUser(User(
    id = Some(1L),
    email = "test@example.com",
    name = "Test User",
    password = None,
    createdAt = Some(LocalDateTime.now()),
    updatedAt = Some(LocalDateTime.now())
  ))

  val otherUser: AuthUser = AuthUser(User(
    id = Some(2L),
    email = "other@example.com",
    name = "Other User",
    password = None,
    createdAt = Some(LocalDateTime.now()),
    updatedAt = Some(LocalDateTime.now())
  ))

  class FakeAuthAction(user: AuthUser) extends AuthAction(null, null, null, null, null)(ec) {
    override def invokeBlock[A](request: Request[A], block: AuthRequest[A] => Future[Result]): Future[Result] = {
      val fakeAuthenticator = null.asInstanceOf[JWTAuthenticator]
      block(AuthRequest("fake-token", user, fakeAuthenticator, request))
    }
  }

  class StubLinkRepository extends LinkRepository(null)(ec) {
    private var links = Map.empty[Long, (JsObject, Long)]

    def putLink(id: Long, userId: Long, status: String, title: String = "Markko Note"): Unit = {
      links = links.updated(
        id,
        (
          Json.obj(
            "id" -> id,
            "url" -> s"https://example.com/$id",
            "urlHash" -> s"hash-$id",
            "title" -> title,
            "contentMd" -> s"Saved content for $id",
            "readingTimeMin" -> 4,
            "status" -> status,
            "collectionId" -> JsNull,
            "userId" -> userId,
            "savedAt" -> "2026-03-28T10:00:00Z",
            "parsedAt" -> "2026-03-28T10:05:00Z",
            "tags" -> Json.arr("scala", "markko")
          ),
          userId
        )
      )
    }

    override def findById(id: Long, userId: Option[Long]): Future[Option[JsObject]] =
      Future.successful(
        links.get(id).flatMap {
          case (json, ownerId) =>
            userId match {
              case Some(uid) if uid != ownerId => None
              case _ => Some(json)
            }
        }
      )
  }

  class StubExportJobRepository extends ExportJobRepository(null)(ec) {
    private var nextId = 1L
    private var jobsByLink = Map.empty[Long, List[JsObject]]

    override def insert(linkId: Long): Future[Long] = Future.successful {
      val id = nextId
      nextId += 1
      val job = Json.obj(
        "id" -> id,
        "linkId" -> linkId,
        "status" -> "pending",
        "vaultPath" -> JsNull,
        "errorMsg" -> JsNull,
        "createdAt" -> "2026-03-28T10:10:00Z",
        "completedAt" -> JsNull
      )
      jobsByLink = jobsByLink.updated(linkId, job :: jobsByLink.getOrElse(linkId, Nil))
      id
    }

    override def findByLinkId(linkId: Long): Future[List[JsObject]] =
      Future.successful(jobsByLink.getOrElse(linkId, Nil))

    override def findLatestSuccess(linkId: Long): Future[Option[JsObject]] =
      Future.successful(
        jobsByLink
          .getOrElse(linkId, Nil)
          .find(job => (job \ "status").as[String] == "done")
      )

    override def findLatestExportedPath(linkId: Long): Future[Option[JsObject]] =
      Future.successful(
        jobsByLink
          .getOrElse(linkId, Nil)
          .find(job => (job \ "vaultPath").asOpt[String].exists(_.nonEmpty))
      )

    def seedJob(linkId: Long, job: JsObject): Unit = {
      jobsByLink = jobsByLink.updated(linkId, job :: jobsByLink.getOrElse(linkId, Nil))
    }
  }

  class StubRedisService extends RedisService(testConfig, null)(ec) {
    var enqueuedExportIds = Vector.empty[Long]
    var enqueuedDeleteIds = Vector.empty[Long]

    override def enqueueExportJob(linkId: Long): Future[Unit] = Future.successful {
      enqueuedExportIds = enqueuedExportIds :+ linkId
    }

    override def enqueueDeleteJob(linkId: Long): Future[Unit] = Future.successful {
      enqueuedDeleteIds = enqueuedDeleteIds :+ linkId
    }
  }

  "ExportController GET /export/:id" must {
    "return markdown for an owned parsed link" in {
      val linkRepo = new StubLinkRepository()
      linkRepo.putLink(1L, testUser.id.get, status = "parsed", title = "Deep Scala")
      val controller = buildController(linkRepo, new StubExportJobRepository(), new StubRedisService(), testUser)

      val result = controller.download(1L).apply(FakeRequest(GET, "/export/1"))

      status(result) mustBe OK
      contentType(result) mustBe Some("text/markdown")
      val body = contentAsString(result)
      body must include("# Deep Scala")
      body must include("Saved content for 1")
      headers(result).get(CONTENT_DISPOSITION).value must include("deep-scala.md")
    }

    "return 404 when the link is not visible to the current user" in {
      val linkRepo = new StubLinkRepository()
      linkRepo.putLink(2L, otherUser.id.get, status = "parsed")
      val controller = buildController(linkRepo, new StubExportJobRepository(), new StubRedisService(), testUser)

      status(controller.download(2L).apply(FakeRequest(GET, "/export/2"))) mustBe NOT_FOUND
    }
  }

  "ExportController GET /export/:id/status" must {
    "return export jobs for an owned link" in {
      val linkRepo = new StubLinkRepository()
      linkRepo.putLink(3L, testUser.id.get, status = "parsed")
      val exportRepo = new StubExportJobRepository()
      exportRepo.seedJob(3L, Json.obj(
        "id" -> 11L,
        "linkId" -> 3L,
        "status" -> "done",
        "vaultPath" -> "vault/collections/scala.md",
        "errorMsg" -> JsNull,
        "createdAt" -> "2026-03-28T09:55:00Z",
        "completedAt" -> "2026-03-28T10:00:00Z"
      ))
      val controller = buildController(linkRepo, exportRepo, new StubRedisService(), testUser)

      val result = controller.status(3L).apply(FakeRequest(GET, "/export/3/status"))

      status(result) mustBe OK
      val json = contentAsJson(result).as[JsArray].value
      json must have size 1
      (json.head \ "status").as[String] mustBe "done"
    }

    "return 404 when the link does not exist for the current user" in {
      val controller = buildController(new StubLinkRepository(), new StubExportJobRepository(), new StubRedisService(), testUser)
      status(controller.status(999L).apply(FakeRequest(GET, "/export/999/status"))) mustBe NOT_FOUND
    }
  }

  "ExportController POST /export/:id/reexport" must {
    "queue a re-export for a parsed link" in {
      val linkRepo = new StubLinkRepository()
      linkRepo.putLink(4L, testUser.id.get, status = "parsed")
      val exportRepo = new StubExportJobRepository()
      val redisService = new StubRedisService()
      val controller = buildController(linkRepo, exportRepo, redisService, testUser)

      val result = controller.reexport(4L).apply(FakeRequest(POST, "/export/4/reexport"))

      status(result) mustBe ACCEPTED
      (contentAsJson(result) \ "linkId").as[Long] mustBe 4L
      redisService.enqueuedExportIds mustBe Vector(4L)
      contentAsJson(result).\("exportJobId").as[Long] mustBe 1L
    }

    "return 400 when the link is not parsed yet" in {
      val linkRepo = new StubLinkRepository()
      linkRepo.putLink(5L, testUser.id.get, status = "pending")
      val redisService = new StubRedisService()
      val controller = buildController(linkRepo, new StubExportJobRepository(), redisService, testUser)

      val result = controller.reexport(5L).apply(FakeRequest(POST, "/export/5/reexport"))

      status(result) mustBe BAD_REQUEST
      redisService.enqueuedExportIds mustBe empty
    }
  }

  "ExportController DELETE /export/:id" must {
    "queue vault deletion when a successful export exists" in {
      val linkRepo = new StubLinkRepository()
      linkRepo.putLink(6L, testUser.id.get, status = "parsed")
      val exportRepo = new StubExportJobRepository()
      exportRepo.seedJob(6L, Json.obj(
        "id" -> 21L,
        "linkId" -> 6L,
        "status" -> "done",
        "vaultPath" -> "vault/by-tag/scala.md",
        "createdAt" -> "2026-03-28T09:00:00Z",
        "completedAt" -> "2026-03-28T09:01:00Z"
      ))
      val redisService = new StubRedisService()
      val controller = buildController(linkRepo, exportRepo, redisService, testUser)

      val result = controller.deleteExport(6L).apply(FakeRequest(DELETE, "/export/6"))

      status(result) mustBe OK
      redisService.enqueuedDeleteIds mustBe Vector(6L)
      (contentAsJson(result) \ "vaultPath").as[String] mustBe "vault/by-tag/scala.md"
    }

    "return 404 when there is no successful export to delete" in {
      val linkRepo = new StubLinkRepository()
      linkRepo.putLink(7L, testUser.id.get, status = "parsed")
      val controller = buildController(linkRepo, new StubExportJobRepository(), new StubRedisService(), testUser)

      status(controller.deleteExport(7L).apply(FakeRequest(DELETE, "/export/7"))) mustBe NOT_FOUND
    }
  }

  private def buildController(
    linkRepo: StubLinkRepository,
    exportRepo: StubExportJobRepository,
    redisService: StubRedisService,
    user: AuthUser
  ): ExportController =
    new ExportController(
      stubControllerComponents(),
      linkRepo,
      exportRepo,
      redisService,
      new FakeAuthAction(user)
    )

  override protected def afterAll(): Unit = {
    actorSystem.terminate()
    super.afterAll()
  }
}
