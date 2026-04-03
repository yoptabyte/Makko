package controllers

import actions.AuthAction
import cats.effect.unsafe.implicits.global
import io.markko.shared.domain._
import javax.inject.{Inject, Singleton}
import play.api.libs.json.{JsError, JsSuccess, Json}
import play.api.mvc._
import play.silhouette.api.services.AuthenticatorService
import play.silhouette.api.util.{Credentials, PasswordHasherRegistry}
import play.silhouette.impl.authenticators.JWTAuthenticator
import play.silhouette.impl.providers.CredentialsProvider
import repositories.UserRepository
import services.{RefreshTokenService, TokenBlacklistService, UserService}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class AuthController @Inject()(
  cc: ControllerComponents,
  userRepository: UserRepository,
  userService: UserService,
  passwordHasherRegistry: PasswordHasherRegistry,
  credentialsProvider: CredentialsProvider,
  authenticatorService: AuthenticatorService[JWTAuthenticator],
  authAction: AuthAction,
  tokenBlacklistService: TokenBlacklistService,
  refreshTokenService: RefreshTokenService
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  def register = Action.async(parse.json) { implicit request =>
    request.body.validate[UserCreate] match {
      case JsSuccess(userCreate, _) =>
        userRepository.existsByEmail(userCreate.email).unsafeToFuture().flatMap { exists =>
          if (exists) {
            Future.successful(Conflict(Json.obj("error" -> "User with this email already exists")))
          } else {
            val passwordInfo = passwordHasherRegistry.current.hash(userCreate.password)
            val user = User(
              id = None,
              email = userCreate.email,
              name = userCreate.name,
              password = Some(passwordInfo.password)
            )

            userRepository.create(user).unsafeToFuture().map { userId =>
              val createdUser = user.copy(id = Some(userId))
              val userResponse = UserResponse.from(createdUser).getOrElse(
                throw new IllegalStateException("Created user has no ID")
              )
              Created(Json.obj(
                "message" -> "User created successfully",
                "user" -> Json.toJson(userResponse)
              ))
            }
          }
        }
      case JsError(errors) =>
        Future.successful(BadRequest(Json.obj("error" -> "Invalid JSON", "details" -> JsError.toJson(errors))))
    }
  }

  def login = Action.async(parse.json) { implicit request =>
    request.body.validate[UserLogin] match {
      case JsSuccess(userLogin, _) =>
        credentialsProvider.authenticate(Credentials(userLogin.email, userLogin.password)).flatMap { loginInfo =>
          userService.retrieve(loginInfo).flatMap {
            case Some(user) =>
              issueSession(loginInfo, user.user).map(response => Ok(Json.toJson(response)))
            case None =>
              Future.successful(Unauthorized(Json.obj("error" -> "User not found")))
          }
        }.recover {
          case NonFatal(_) =>
            Unauthorized(Json.obj("error" -> "Invalid credentials"))
        }
      case JsError(errors) =>
        Future.successful(BadRequest(Json.obj("error" -> "Invalid JSON", "details" -> JsError.toJson(errors))))
    }
  }

  def refresh = Action.async(parse.json) { implicit request =>
    request.body.validate[RefreshTokenRequest] match {
      case JsSuccess(refreshRequest, _) =>
        refreshTokenService.rotate(refreshRequest.refreshToken, request).flatMap {
          case RefreshTokenService.Rotated(loginInfo, newRefreshToken, metadata) =>
            userService.retrieve(loginInfo).flatMap {
              case Some(user) =>
                issueAccessToken(loginInfo, metadata.sessionId).map { accessToken =>
                  Ok(Json.toJson(sessionResponse(accessToken, newRefreshToken, metadata, user.user)))
                }
              case None =>
                refreshTokenService.revoke(newRefreshToken).map(_ =>
                  Unauthorized(Json.obj("error" -> "User not found"))
                )
            }
          case RefreshTokenService.ReuseDetected =>
            Future.successful(Unauthorized(Json.obj("error" -> "Refresh token reuse detected; session revoked")))
          case RefreshTokenService.InvalidToken =>
            Future.successful(Unauthorized(Json.obj("error" -> "Invalid refresh token")))
        }
      case JsError(errors) =>
        Future.successful(BadRequest(Json.obj("error" -> "Invalid JSON", "details" -> JsError.toJson(errors))))
    }
  }

  def me = authAction.async { implicit request =>
    UserResponse.from(request.identity.user) match {
      case Some(userResp) => Future.successful(Ok(Json.obj("user" -> Json.toJson(userResp))))
      case None => Future.successful(InternalServerError(Json.obj("error" -> "User ID not available")))
    }
  }

  def logout = authAction.async(parse.tolerantJson) { implicit request =>
    val refreshToken = request.body.asOpt[LogoutRequest].flatMap(_.refreshToken)
      .orElse(request.headers.get("X-Refresh-Token").filter(_.nonEmpty))

    val sessionId = request.authenticator.customClaims.flatMap(claims => (claims \ "sid").asOpt[String])

    for {
      _ <- tokenBlacklistService.blacklist(
        request.token,
        request.authenticator.expirationDateTime.toInstant
      )
      _ <- refreshToken match {
        case Some(token) => refreshTokenService.revoke(token)
        case None =>
          sessionId match {
            case Some(value) => refreshTokenService.revokeSession(value)
            case None => Future.successful(())
          }
      }
    } yield Ok(Json.obj("message" -> "Logged out successfully"))
  }

  private def issueSession(
    loginInfo: play.silhouette.api.LoginInfo,
    user: User
  )(implicit request: RequestHeader): Future[AuthTokenResponse] =
    for {
      issuedRefresh <- refreshTokenService.issue(loginInfo, request)
      accessToken <- issueAccessToken(loginInfo, issuedRefresh.metadata.sessionId)
    } yield sessionResponse(accessToken, issuedRefresh.token, issuedRefresh.metadata, user)

  private def issueAccessToken(
    loginInfo: play.silhouette.api.LoginInfo,
    sessionId: String
  )(implicit request: RequestHeader): Future[String] =
    authenticatorService.create(loginInfo).flatMap { authenticator =>
      authenticatorService.init(authenticator.copy(customClaims = Some(Json.obj("sid" -> sessionId))))
    }

  private def sessionResponse(
    accessToken: String,
    refreshToken: String,
    metadata: RefreshSessionMetadata,
    user: User
  ): AuthTokenResponse =
    AuthTokenResponse(
      accessToken = accessToken,
      refreshToken = refreshToken,
      tokenType = "Bearer",
      user = UserResponse.from(user).getOrElse(
        throw new IllegalStateException("Authenticated user has no ID")
      ),
      session = metadata
    )
}
