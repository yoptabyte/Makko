package actions

import auth.AuthUser
import javax.inject.Inject
import play.api.libs.json.Json
import play.api.libs.typedmap.TypedKey
import play.api.mvc._
import play.silhouette.impl.authenticators.JWTAuthenticator
import services.{JwtService, RefreshTokenService, TokenBlacklistService, UserService}

import scala.concurrent.{ExecutionContext, Future}

final case class AuthContext(
  token: String,
  identity: AuthUser,
  authenticator: JWTAuthenticator
)

final case class AuthRequest[A](
  token: String,
  identity: AuthUser,
  authenticator: JWTAuthenticator,
  request: Request[A]
) extends WrappedRequest[A](request)

class AuthAction @Inject()(
  bodyParser: BodyParsers.Default,
  jwtService: JwtService,
  userService: UserService,
  tokenBlacklistService: TokenBlacklistService,
  refreshTokenService: RefreshTokenService
)(implicit ec: ExecutionContext) extends ActionBuilder[AuthRequest, AnyContent] {

  override def parser: BodyParser[AnyContent] = bodyParser
  override protected def executionContext: ExecutionContext = ec

  override def invokeBlock[A](request: Request[A], block: AuthRequest[A] => Future[Result]): Future[Result] =
    authenticate(request).flatMap {
      case Right(context) =>
        val enrichedRequest = request
          .addAttr(AuthAction.TokenAttr, context.token)
          .addAttr(AuthAction.UserAttr, context.identity)
          .addAttr(AuthAction.AuthenticatorAttr, context.authenticator)
        block(AuthRequest(context.token, context.identity, context.authenticator, enrichedRequest))
      case Left(result) =>
        Future.successful(result)
    }

  def authenticate(request: RequestHeader): Future[Either[Result, AuthContext]] =
    extractToken(request) match {
      case Some(token) =>
        tokenBlacklistService.isBlacklisted(token).flatMap {
          case true =>
            Future.successful(Left(Results.Unauthorized(Json.obj("error" -> "Token has been revoked"))))
          case false =>
            jwtService.unserialize(token).toOption.filter(_.isValid) match {
              case Some(authenticator) =>
                withSessionCheck(token, authenticator)
              case None =>
                Future.successful(Left(Results.Unauthorized(Json.obj("error" -> "Invalid token"))))
            }
        }
      case None =>
        Future.successful(Left(Results.Unauthorized(Json.obj("error" -> "Missing token"))))
    }

  private def withSessionCheck(
    token: String,
    authenticator: JWTAuthenticator
  ): Future[Either[Result, AuthContext]] =
    sessionId(authenticator) match {
      case Some(sid) =>
        refreshTokenService.isSessionRevoked(sid).flatMap {
          case true =>
            Future.successful(Left(Results.Unauthorized(Json.obj("error" -> "Session has been revoked"))))
          case false =>
            resolveIdentity(token, authenticator)
        }
      case None =>
        resolveIdentity(token, authenticator)
    }

  private def resolveIdentity(
    token: String,
    authenticator: JWTAuthenticator
  ): Future[Either[Result, AuthContext]] =
    userService.retrieve(authenticator.loginInfo).map {
      case Some(identity) =>
        Right(AuthContext(token, identity, authenticator))
      case None =>
        Left(Results.Unauthorized(Json.obj("error" -> "User not found")))
    }

  private def sessionId(authenticator: JWTAuthenticator): Option[String] =
    authenticator.customClaims.flatMap(claims => (claims \ "sid").asOpt[String])

  private def extractToken(request: RequestHeader): Option[String] =
    request.headers.get("Authorization").collect {
      case header if header.startsWith("Bearer ") => header.drop(7).trim
    }.filter(_.nonEmpty)
      .orElse(request.headers.get("token").filter(_.nonEmpty))
      .orElse(request.getQueryString("token").filter(_.nonEmpty))
}

object AuthAction {
  val TokenAttr = TypedKey[String]("auth.token")
  val UserAttr = TypedKey[AuthUser]("auth.user")
  val AuthenticatorAttr = TypedKey[JWTAuthenticator]("auth.authenticator")

  def getToken(implicit request: RequestHeader): Option[String] =
    request.attrs.get(TokenAttr)

  def getUser(implicit request: RequestHeader): Option[AuthUser] =
    request.attrs.get(UserAttr)

  def getUserId(implicit request: RequestHeader): Option[Long] =
    getUser.flatMap(_.id)

  def getUserEmail(implicit request: RequestHeader): Option[String] =
    getUser.map(_.email)
}
