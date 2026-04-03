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
import play.api.test.Helpers._
import play.api.test._
import play.silhouette.impl.authenticators.JWTAuthenticator
import services.{ElasticsearchService, SearchResult}

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class SearchControllerSpec extends PlaySpec with Results with BeforeAndAfterAll {

  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit lazy val actorSystem: ActorSystem = ActorSystem("search-controller-spec")
  implicit lazy val mat: Materializer = SystemMaterializer(actorSystem).materializer

  private val testUser: AuthUser = AuthUser(User(
    id = Some(42L),
    email = "search@example.com",
    name = "Search User",
    password = None,
    createdAt = Some(Instant.now()),
    updatedAt = Some(Instant.now())
  ))

  class FakeAuthAction(user: AuthUser) extends AuthAction(
    null,
    null, null, null, null
  )(ec) {
    override def invokeBlock[A](request: Request[A], block: AuthRequest[A] => Future[Result]): Future[Result] = {
      val fakeAuthenticator = null.asInstanceOf[JWTAuthenticator]
      block(AuthRequest("fake-token", user, fakeAuthenticator, request))
    }
  }

  class StubElasticsearchService extends ElasticsearchService(
    Configuration.from(Map(
      "markko.elasticsearch.host" -> "localhost",
      "markko.elasticsearch.port" -> 9200,
      "markko.elasticsearch.scheme" -> "http",
      "markko.elasticsearch.username" -> "",
      "markko.elasticsearch.password" -> ""
    ))
  )(ec) {
    var lastUserId: Option[Long] = None
    var lastQuery: Option[String] = None
    var lastTag: Option[String] = None
    var lastCollection: Option[String] = None
    var lastLimit: Option[Int] = None
    var lastOffset: Option[Int] = None

    override def search(query: String, userId: Long, limit: Int): Future[List[JsObject]] = {
      lastUserId = Some(userId)
      lastQuery = Some(query)
      lastLimit = Some(limit)
      Future.successful(List(Json.obj("id" -> 1, "title" -> "Result")))
    }

    override def searchByTag(tag: String, userId: Long, limit: Int): Future[List[JsObject]] = {
      lastUserId = Some(userId)
      lastTag = Some(tag)
      lastLimit = Some(limit)
      Future.successful(List(Json.obj("id" -> 2, "tag" -> tag)))
    }

    override def searchAdvanced(
      query: Option[String],
      tags: List[String],
      collection: Option[String],
      userId: Long,
      limit: Int,
      offset: Int
    ): Future[SearchResult] = {
      lastUserId = Some(userId)
      lastQuery = query
      lastTag = tags.headOption
      lastCollection = collection
      lastLimit = Some(limit)
      lastOffset = Some(offset)
      Future.successful(SearchResult(
        total = 1,
        hits = List(Json.obj("id" -> 3, "title" -> "Advanced")),
        offset = offset,
        limit = limit
      ))
    }

    override def suggest(prefix: String, userId: Long, limit: Int): Future[List[JsObject]] = {
      lastUserId = Some(userId)
      lastQuery = Some(prefix)
      lastLimit = Some(limit)
      Future.successful(List(Json.obj("id" -> 4, "title" -> "Suggestion")))
    }
  }

  "SearchController GET /links/search" must {
    "pass authenticated user id into Elasticsearch search" in {
      val esService = new StubElasticsearchService()
      val controller = new SearchController(stubControllerComponents(), esService, new FakeAuthAction(testUser))

      val result = controller.search("scala").apply(FakeRequest(GET, "/links/search?q=scala"))

      status(result) mustBe OK
      esService.lastUserId mustBe Some(42L)
      esService.lastQuery mustBe Some("scala")
      esService.lastLimit mustBe Some(20)
    }
  }

  "SearchController GET /links/search/advanced" must {
    "pass filters and authenticated user id into advanced search" in {
      val esService = new StubElasticsearchService()
      val controller = new SearchController(stubControllerComponents(), esService, new FakeAuthAction(testUser))

      val result = controller.searchAdvanced(
        q = Some("pekko"),
        tag = Some("scala"),
        collection = Some("backend"),
        limit = Some(10),
        offset = Some(5)
      ).apply(FakeRequest(GET, "/links/search/advanced"))

      status(result) mustBe OK
      esService.lastUserId mustBe Some(42L)
      esService.lastQuery mustBe Some("pekko")
      esService.lastTag mustBe Some("scala")
      esService.lastCollection mustBe Some("backend")
      esService.lastLimit mustBe Some(10)
      esService.lastOffset mustBe Some(5)
    }
  }

  "SearchController GET /links/search/tag/:tag" must {
    "pass authenticated user id into tag search" in {
      val esService = new StubElasticsearchService()
      val controller = new SearchController(stubControllerComponents(), esService, new FakeAuthAction(testUser))

      val result = controller.searchByTag("distributed-systems").apply(FakeRequest(GET, "/links/search/tag/distributed-systems"))

      status(result) mustBe OK
      esService.lastUserId mustBe Some(42L)
      esService.lastTag mustBe Some("distributed-systems")
    }
  }

  "SearchController GET /links/suggest" must {
    "pass authenticated user id into suggestions" in {
      val esService = new StubElasticsearchService()
      val controller = new SearchController(stubControllerComponents(), esService, new FakeAuthAction(testUser))

      val result = controller.suggest("mar").apply(FakeRequest(GET, "/links/suggest?q=mar"))

      status(result) mustBe OK
      esService.lastUserId mustBe Some(42L)
      esService.lastQuery mustBe Some("mar")
      esService.lastLimit mustBe Some(5)
    }
  }

  override protected def afterAll(): Unit = {
    actorSystem.terminate()
    super.afterAll()
  }
}
