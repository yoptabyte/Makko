package controllers

import actions.{AuthAction, AuthRequest}
import auth.AuthUser
import io.markko.shared.domain.User
import org.apache.pekko.actor.ActorSystem
import org.scalatestplus.play._
import org.apache.pekko.stream.{Materializer, SystemMaterializer}
import org.scalatest.BeforeAndAfterAll
import play.api.Configuration
import play.api.libs.json._
import play.api.mvc._
import play.api.test._
import play.api.test.Helpers._
import play.silhouette.impl.authenticators.JWTAuthenticator
import repositories.LinkRepository
import services.{ElasticsearchService, RedisService}

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

class LinkControllerSpec extends PlaySpec with Results with BeforeAndAfterAll {

  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit lazy val actorSystem: ActorSystem = ActorSystem("link-controller-spec")
  implicit lazy val mat: Materializer = SystemMaterializer(actorSystem).materializer
  private val testConfig = Configuration.from(Map(
    "markko.redis.host" -> "localhost",
    "markko.redis.port" -> 6379,
    "markko.elasticsearch.host" -> "localhost",
    "markko.elasticsearch.port" -> 9200,
    "markko.elasticsearch.scheme" -> "http",
    "markko.elasticsearch.username" -> "",
    "markko.elasticsearch.password" -> ""
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

  // ---------- Stub AuthAction that always returns testUser ----------

  class FakeAuthAction(user: AuthUser) extends AuthAction(
    null, null, null, null, null
  )(ec) {
    override def invokeBlock[A](request: Request[A], block: AuthRequest[A] => Future[Result]): Future[Result] = {
      val fakeAuthenticator = null.asInstanceOf[JWTAuthenticator]
      block(AuthRequest("fake-token", user, fakeAuthenticator, request))
    }
  }

  // ---------- Stub RedisService ----------

  class StubRedisService extends RedisService(testConfig, null)(ec) {
    private var seen = Set.empty[String]

    override def checkDedup(urlHash: String, userId: Long): Future[Boolean] =
      Future.successful(seen.contains(s"$userId:$urlHash"))

    override def setDedup(urlHash: String, userId: Long): Future[Unit] = {
      seen = seen + s"$userId:$urlHash"
      Future.successful(())
    }

    override def enqueueParseJob(linkId: Long): Future[Unit] =
      Future.successful(())
  }

  // ---------- Stub LinkRepository ----------

  class StubLinkRepository extends LinkRepository(null)(ec) {
    private var links = Map.empty[Long, (JsObject, Long)] // id -> (json, userId)
    private var nextId: Long = 1L

    override def insert(
      url: String,
      urlHash: String,
      collectionId: Option[Long],
      userId: Long,
      exportDirectory: Option[String],
      exportFileName: Option[String]
    ): Future[Long] = {
      val id = nextId
      nextId += 1
      val json = Json.obj(
        "id" -> id, "url" -> url, "urlHash" -> urlHash, "title" -> JsNull,
        "status" -> "pending", "collectionId" -> collectionId, "userId" -> userId,
        "exportDirectory" -> exportDirectory, "exportFileName" -> exportFileName,
        "savedAt" -> java.time.Instant.now().toString, "tags" -> Json.arr()
      )
      links = links + (id -> (json, userId))
      Future.successful(id)
    }

    override def findById(id: Long, userId: Option[Long]): Future[Option[JsObject]] =
      Future.successful(links.get(id).flatMap {
        case (json, owner) => userId match {
          case Some(uid) if uid != owner => None
          case _ => Some(json)
        }
      })

    override def findAllByUser(userId: Long, limit: Int, offset: Int): Future[List[JsObject]] =
      Future.successful(links.values.collect { case (json, owner) if owner == userId => json }.toList)

    override def countByUser(userId: Long): Future[Long] =
      Future.successful(links.values.count { case (_, owner) => owner == userId }.toLong)

    override def attachTags(linkId: Long, tagNames: List[String]): Future[Unit] =
      Future.successful(())

    override def delete(id: Long, userId: Long): Future[Boolean] =
      Future.successful {
        links.get(id) match {
          case Some((_, owner)) if owner == userId =>
            links = links - id
            true
          case _ => false
        }
      }

    override def update(
      id: Long,
      userId: Long,
      collectionId: Option[Long],
      tags: List[String],
      exportDirectory: Option[String],
      exportFileName: Option[String]
    ): Future[Boolean] =
      Future.successful(links.get(id).exists(_._2 == userId))
  }

  // ---------- Stub ElasticsearchService ----------

  class StubEsService extends ElasticsearchService(testConfig)(ec) {
    override def deleteLink(linkId: Long): Future[Unit] = Future.successful(())
  }

  // ---------- Tests ----------

  "LinkController POST /links" must {

    "return 201 when creating a new link" in {
      val (controller, _) = buildController()
      val body = Json.obj("url" -> "https://example.com", "tags" -> Json.arr("scala", "fp"))

      val request = FakeRequest(POST, "/links")
        .withBody(body)
        .withHeaders(CONTENT_TYPE -> "application/json")

      val result = controller.create().apply(request)
      status(result) mustBe CREATED
      val json = contentAsJson(result)
      (json \ "url").as[String] mustBe "https://example.com"
    }

    "return 409 for duplicate URL" in {
      val (controller, _) = buildController()
      val body = Json.obj("url" -> "https://duplicate.com")

      // First create
      val request1 = FakeRequest(POST, "/links")
        .withBody(body)
        .withHeaders(CONTENT_TYPE -> "application/json")
      status(controller.create().apply(request1)) mustBe CREATED

      // Second create — same URL
      val request2 = FakeRequest(POST, "/links")
        .withBody(body)
        .withHeaders(CONTENT_TYPE -> "application/json")
      val result = controller.create().apply(request2)
      status(result) mustBe CONFLICT
    }

    "persist export directory and file name overrides" in {
      val (controller, _) = buildController()
      val body = Json.obj(
        "url" -> "https://example.com/reporting",
        "exportDirectory" -> "sales/q1",
        "exportFileName" -> "Follow Up Boss Reporting"
      )

      val request = FakeRequest(POST, "/links")
        .withBody(body)
        .withHeaders(CONTENT_TYPE -> "application/json")

      val result = controller.create().apply(request)
      status(result) mustBe CREATED

      val json = contentAsJson(result)
      (json \ "exportDirectory").as[String] mustBe "sales/q1"
      (json \ "exportFileName").as[String] mustBe "Follow Up Boss Reporting"
    }
  }

  "LinkController GET /links" must {
    "return paginated list of user's links" in {
      val (controller, _) = buildController()

      // Create a link first
      val createBody = Json.obj("url" -> "https://example.com")
      val createReq = FakeRequest(POST, "/links")
        .withBody(createBody)
        .withHeaders(CONTENT_TYPE -> "application/json")
      status(controller.create().apply(createReq)) mustBe CREATED

      val request = FakeRequest(GET, "/links")
      val result = controller.list(None, None).apply(request)
      status(result) mustBe OK
      val json = contentAsJson(result)
      (json \ "total").as[Long] must be >= 1L
      (json \ "links").as[JsArray].value must not be empty
    }
  }

  "LinkController GET /links/:id" must {
    "return 404 for non-existent link" in {
      val (controller, _) = buildController()
      val request = FakeRequest(GET, "/links/999")
      val result = controller.get(999L).apply(request)
      status(result) mustBe NOT_FOUND
    }
  }

  "LinkController DELETE /links/:id" must {
    "return 200 when deleting own link" in {
      val (controller, _) = buildController()

      // Create first
      val createBody = Json.obj("url" -> "https://to-delete.com")
      val createReq = FakeRequest(POST, "/links")
        .withBody(createBody)
        .withHeaders(CONTENT_TYPE -> "application/json")
      val createResult = controller.create().apply(createReq)
      val linkId = (contentAsJson(createResult) \ "id").as[Long]

      // Delete
      val delReq = FakeRequest(DELETE, s"/links/$linkId")
      val delResult = controller.delete(linkId).apply(delReq)
      status(delResult) mustBe OK
    }

    "return 404 when deleting non-existent link" in {
      val (controller, _) = buildController()
      val request = FakeRequest(DELETE, "/links/999")
      val result = controller.delete(999L).apply(request)
      status(result) mustBe NOT_FOUND
    }
  }

  // ---------- Helpers ----------

  private def buildController(user: AuthUser = testUser): (LinkController, StubRedisService) = {
    val cc = stubControllerComponents()
    val authAction = new FakeAuthAction(user)
    val redisService = new StubRedisService()
    val linkRepo = new StubLinkRepository()
    val esService = new StubEsService()

    val controller = new LinkController(cc, linkRepo, redisService, esService, authAction)
    (controller, redisService)
  }

  override protected def afterAll(): Unit = {
    actorSystem.terminate()
    super.afterAll()
  }
}
