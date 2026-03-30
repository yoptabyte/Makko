package controllers

import actions.{AuthAction, AuthRequest}
import auth.AuthUser
import io.markko.shared.domain.User
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{Materializer, SystemMaterializer}
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.play.PlaySpec
import play.api.mvc._
import play.api.test.Helpers._
import play.api.test._
import play.silhouette.impl.authenticators.JWTAuthenticator
import repositories.CollectionRepository

import java.time.{Instant, LocalDateTime}
import scala.concurrent.{ExecutionContext, Future}

class CollectionControllerSpec extends PlaySpec with Results with BeforeAndAfterAll {

  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit lazy val actorSystem: ActorSystem = ActorSystem("collection-controller-spec")
  implicit lazy val mat: Materializer = SystemMaterializer(actorSystem).materializer

  private val now = Instant.parse("2026-03-28T10:15:30Z")

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

  class StubCollectionRepository extends CollectionRepository(null)(ec) {
    private case class StoredCollection(id: Long, name: String, slug: String, userId: Long, createdAt: Instant)

    private var collections = Map.empty[Long, StoredCollection]
    private var nextId = 1L

    override def insert(name: String, slug: String, userId: Long): Future[Long] = Future.successful {
      val id = nextId
      nextId += 1
      collections = collections.updated(id, StoredCollection(id, name, slug, userId, now))
      id
    }

    override def findAllByUser(userId: Long): Future[List[play.api.libs.json.JsObject]] =
      Future.successful {
        collections.values
          .filter(_.userId == userId)
          .toList
          .sortBy(_.name)
          .map { collection =>
            play.api.libs.json.Json.obj(
              "id" -> collection.id,
              "name" -> collection.name,
              "slug" -> collection.slug,
              "createdAt" -> collection.createdAt.toString,
              "linkCount" -> 0
            )
          }
      }

    override def update(id: Long, userId: Long, name: String, slug: String): Future[Boolean] =
      Future.successful {
        collections.get(id) match {
          case Some(existing) if existing.userId == userId =>
            collections = collections.updated(id, existing.copy(name = name, slug = slug))
            true
          case _ => false
        }
      }

    override def delete(id: Long, userId: Long): Future[Boolean] =
      Future.successful {
        collections.get(id) match {
          case Some(existing) if existing.userId == userId =>
            collections = collections - id
            true
          case _ => false
        }
      }
  }

  "CollectionController GET /collections" must {
    "list only the current user's collections" in {
      val repo = new StubCollectionRepository()
      await(repo.insert("Backend", "backend", testUser.id.get))
      await(repo.insert("Private", "private", otherUser.id.get))

      val controller = buildController(repo, testUser)
      val result = controller.list().apply(FakeRequest(GET, "/collections"))

      status(result) mustBe OK
      val json = contentAsJson(result).as[play.api.libs.json.JsArray].value
      json must have size 1
      (json.head \ "name").as[String] mustBe "Backend"
    }
  }

  "CollectionController POST /collections" must {
    "create a collection and return its slug" in {
      val controller = buildController(new StubCollectionRepository(), testUser)
      val request = FakeRequest(POST, "/collections")
        .withBody(play.api.libs.json.Json.obj("name" -> "Read Later"))
        .withHeaders(CONTENT_TYPE -> "application/json")

      val result = controller.create().apply(request)

      status(result) mustBe CREATED
      val json = contentAsJson(result)
      (json \ "name").as[String] mustBe "Read Later"
      (json \ "slug").as[String] mustBe "read-later"
    }

    "return 400 when name is missing" in {
      val controller = buildController(new StubCollectionRepository(), testUser)
      val request = FakeRequest(POST, "/collections")
        .withBody(play.api.libs.json.Json.obj("slug" -> "missing-name"))
        .withHeaders(CONTENT_TYPE -> "application/json")

      status(controller.create().apply(request)) mustBe BAD_REQUEST
    }
  }

  "CollectionController PUT /collections/:id" must {
    "update an owned collection" in {
      val repo = new StubCollectionRepository()
      val collectionId = await(repo.insert("Inbox", "inbox", testUser.id.get))
      val controller = buildController(repo, testUser)

      val request = FakeRequest(PUT, s"/collections/$collectionId")
        .withBody(play.api.libs.json.Json.obj("name" -> "Deep Research"))
        .withHeaders(CONTENT_TYPE -> "application/json")

      val result = controller.update(collectionId).apply(request)

      status(result) mustBe OK
      val json = contentAsJson(result)
      (json \ "name").as[String] mustBe "Deep Research"
      (json \ "slug").as[String] mustBe "deep-research"
    }

    "return 404 when trying to update another user's collection" in {
      val repo = new StubCollectionRepository()
      val collectionId = await(repo.insert("Other", "other", otherUser.id.get))
      val controller = buildController(repo, testUser)

      val request = FakeRequest(PUT, s"/collections/$collectionId")
        .withBody(play.api.libs.json.Json.obj("name" -> "Renamed"))
        .withHeaders(CONTENT_TYPE -> "application/json")

      status(controller.update(collectionId).apply(request)) mustBe NOT_FOUND
    }
  }

  "CollectionController DELETE /collections/:id" must {
    "delete an owned collection" in {
      val repo = new StubCollectionRepository()
      val collectionId = await(repo.insert("To Delete", "to-delete", testUser.id.get))
      val controller = buildController(repo, testUser)

      val result = controller.delete(collectionId).apply(FakeRequest(DELETE, s"/collections/$collectionId"))

      status(result) mustBe OK
      (contentAsJson(result) \ "id").as[Long] mustBe collectionId
    }

    "return 404 when collection does not belong to the current user" in {
      val repo = new StubCollectionRepository()
      val collectionId = await(repo.insert("Other", "other", otherUser.id.get))
      val controller = buildController(repo, testUser)

      status(controller.delete(collectionId).apply(FakeRequest(DELETE, s"/collections/$collectionId"))) mustBe NOT_FOUND
    }
  }

  private def buildController(repo: StubCollectionRepository, user: AuthUser): CollectionController =
    new CollectionController(stubControllerComponents(), repo, new FakeAuthAction(user))

  override protected def afterAll(): Unit = {
    actorSystem.terminate()
    super.afterAll()
  }
}
