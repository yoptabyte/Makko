package controllers

import actions.{AuthAction, AuthRequest}
import auth.AuthUser
import cats.effect.unsafe.implicits.global
import io.markko.shared.domain.{User, UserCreate, UserLogin}
import org.apache.pekko.actor.ActorSystem
import org.scalatestplus.play._
import org.apache.pekko.stream.{Materializer, SystemMaterializer}
import org.scalatest.BeforeAndAfterAll
import play.api.Configuration
import play.api.libs.json._
import play.api.mvc._
import play.api.test._
import play.api.test.Helpers._
import play.silhouette.api.LoginInfo
import play.silhouette.api.services.AuthenticatorService
import play.silhouette.api.util.{Credentials, PasswordHasherRegistry}
import play.silhouette.impl.authenticators.JWTAuthenticator
import play.silhouette.impl.providers.CredentialsProvider
import repositories.UserRepository
import services.{RefreshTokenService, TokenBlacklistService, UserService}

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class AuthControllerSpec extends PlaySpec with Results with BeforeAndAfterAll {

  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit lazy val actorSystem: ActorSystem = ActorSystem("auth-controller-spec")
  implicit lazy val mat: Materializer = SystemMaterializer(actorSystem).materializer

  val testUser: User = User(
    id = Some(1L),
    email = "test@example.com",
    name = "Test User",
    password = Some("$2a$12$hashedpassword"),
    createdAt = Some(Instant.now()),
    updatedAt = Some(Instant.now())
  )

  // ---------- Stub UserRepository ----------

  class StubUserRepository extends UserRepository(null) {
    private var users: Map[String, User] = Map.empty
    private var nextId: Long = 1L

    override def existsByEmail(email: String) =
      cats.effect.IO.pure(users.contains(email))

    override def create(user: User) = cats.effect.IO {
      val id = nextId
      nextId += 1
      val created = user.copy(
        id = Some(id),
        createdAt = Some(Instant.now()),
        updatedAt = Some(Instant.now())
      )
      users = users + (user.email -> created)
      id
    }

    override def findByEmail(email: String) =
      cats.effect.IO.pure(users.get(email))
  }

  // ---------- Tests ----------

  "AuthController POST /auth/register" must {

    "return 201 for a new user" in {
      val userRepo = new StubUserRepository()
      val controller = buildController(userRepo)

      val body = Json.obj(
        "email" -> "new@example.com",
        "name" -> "New User",
        "password" -> "password123"
      )

      val request = FakeRequest(POST, "/auth/register")
        .withBody(body)
        .withHeaders(CONTENT_TYPE -> "application/json")

      val result = controller.register.apply(request)
      status(result) mustBe CREATED
      val json = contentAsJson(result)
      (json \ "message").as[String] mustBe "User created successfully"
    }

    "return 409 for duplicate email" in {
      val userRepo = new StubUserRepository()
      // Pre-create user
      userRepo.create(testUser).unsafeRunSync()

      val controller = buildController(userRepo)

      val body = Json.obj(
        "email" -> testUser.email,
        "name" -> "Another User",
        "password" -> "password123"
      )

      val request = FakeRequest(POST, "/auth/register")
        .withBody(body)
        .withHeaders(CONTENT_TYPE -> "application/json")

      val result = controller.register.apply(request)
      status(result) mustBe CONFLICT
    }

    "return 400 for invalid JSON" in {
      val userRepo = new StubUserRepository()
      val controller = buildController(userRepo)

      val body = Json.obj("email" -> "test@test.com")  // missing name/password

      val request = FakeRequest(POST, "/auth/register")
        .withBody(body)
        .withHeaders(CONTENT_TYPE -> "application/json")

      val result = controller.register.apply(request)
      status(result) mustBe BAD_REQUEST
    }
  }

  // ---------- Helpers ----------

  private def buildController(userRepo: UserRepository): AuthController = {
    val cc = stubControllerComponents()
    val userService = new UserService(userRepo)
    val passwordHasherRegistry = PasswordHasherRegistry(
      new play.silhouette.password.BCryptSha256PasswordHasher()
    )
    // Stubs for services that won't be used in register tests
    val credentialsProvider = null.asInstanceOf[CredentialsProvider]
    val authenticatorService = null.asInstanceOf[AuthenticatorService[JWTAuthenticator]]
    val authAction = null.asInstanceOf[AuthAction]
    val tokenBlacklistService = null.asInstanceOf[TokenBlacklistService]
    val refreshTokenService = null.asInstanceOf[RefreshTokenService]

    new AuthController(
      cc, userRepo, userService, passwordHasherRegistry,
      credentialsProvider, authenticatorService, authAction,
      tokenBlacklistService, refreshTokenService
    )
  }

  override protected def afterAll(): Unit = {
    actorSystem.terminate()
    super.afterAll()
  }
}
