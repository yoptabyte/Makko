package services

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import dev.profunktor.redis4cats.RedisCommands
import io.markko.shared.domain.RefreshSessionMetadata
import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.libs.json.{Json, OFormat}
import play.api.mvc.RequestHeader
import play.silhouette.api.LoginInfo

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.time.LocalDateTime
import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{Duration, FiniteDuration}

@Singleton
class RefreshTokenService @Inject()(
  config: Configuration,
  redis: RedisCommands[IO, String, String]
)(implicit ec: ExecutionContext) {

  import RefreshTokenService._

  private val random = new SecureRandom()
  private val expiry = Duration(
    config.getOptional[String]("markko.jwt.refresh-token-expiry").getOrElse("30 days")
  ).asInstanceOf[FiniteDuration]

  def issue(loginInfo: LoginInfo, request: RequestHeader): Future[IssuedRefreshToken] =
    issue(loginInfo, buildMetadata(generateSessionId(), request, LocalDateTime.now(), None))

  def rotate(token: String, request: RequestHeader): Future[RotateResult] = {
    val tokenHash = sha256(token)

    resolveRecord(token).flatMap {
      case Some(record) =>
        val refreshedMetadata = record.metadata.copy(
          ipAddress = clientIp(request),
          userAgent = request.headers.get("User-Agent"),
          lastRefreshedAt = Some(LocalDateTime.now())
        )

        issue(record.loginInfo, refreshedMetadata).flatMap { issued =>
          for {
            _ <- redis.setEx(usedKey(tokenHash), record.sessionId, expiry).unsafeToFuture()
            _ <- redis.del(tokenKey(tokenHash)).map(_ => ()).unsafeToFuture()
          } yield Rotated(record.loginInfo, issued.token, issued.metadata)
        }
      case None =>
        redis.get(usedKey(tokenHash)).unsafeToFuture().flatMap {
          case Some(sessionId) =>
            revokeSession(sessionId).map(_ => ReuseDetected)
          case None =>
            Future.successful(InvalidToken)
        }
    }
  }

  def revoke(token: String): Future[Unit] =
    resolveRecord(token).flatMap {
      case Some(record) => revokeSession(record.sessionId)
      case None => redis.del(key(token)).map(_ => ()).unsafeToFuture()
    }

  def revokeSession(sessionId: String): Future[Unit] =
    redis.get(sessionKey(sessionId)).unsafeToFuture().flatMap {
      case Some(currentTokenHash) =>
        for {
          _ <- redis.del(tokenKey(currentTokenHash)).map(_ => ()).unsafeToFuture()
          _ <- redis.del(sessionKey(sessionId)).map(_ => ()).unsafeToFuture()
          _ <- redis.setEx(sessionRevokedKey(sessionId), "1", expiry).unsafeToFuture()
        } yield ()
      case None =>
        redis.setEx(sessionRevokedKey(sessionId), "1", expiry).unsafeToFuture()
    }

  def isSessionRevoked(sessionId: String): Future[Boolean] =
    redis.get(sessionRevokedKey(sessionId)).map(_.isDefined).unsafeToFuture()

  private def issue(loginInfo: LoginInfo, metadata: RefreshSessionMetadata): Future[IssuedRefreshToken] = {
    val token = generateToken()
    val tokenHash = sha256(token)
    val record = RefreshTokenRecord(
      providerId = loginInfo.providerID,
      providerKey = loginInfo.providerKey,
      sessionId = metadata.sessionId,
      metadata = metadata
    )

    for {
      _ <- redis.setEx(tokenKey(tokenHash), Json.stringify(Json.toJson(record)(RefreshTokenRecord.format)), expiry).unsafeToFuture()
      _ <- redis.setEx(sessionKey(metadata.sessionId), tokenHash, expiry).unsafeToFuture()
    } yield IssuedRefreshToken(token, metadata)
  }

  private def resolveRecord(token: String): Future[Option[RefreshTokenRecord]] =
    redis.get(key(token)).map(_.flatMap(deserializeRecord)).unsafeToFuture()

  private def deserializeRecord(value: String): Option[RefreshTokenRecord] =
    Json.parse(value).asOpt[RefreshTokenRecord](RefreshTokenRecord.format)

  private def buildMetadata(
    sessionId: String,
    request: RequestHeader,
    createdAt: LocalDateTime,
    lastRefreshedAt: Option[LocalDateTime]
  ): RefreshSessionMetadata =
    RefreshSessionMetadata(
      sessionId = sessionId,
      ipAddress = clientIp(request),
      userAgent = request.headers.get("User-Agent"),
      createdAt = createdAt,
      lastRefreshedAt = lastRefreshedAt
    )

  private def clientIp(request: RequestHeader): String =
    request.headers.get("X-Forwarded-For")
      .flatMap(_.split(",").headOption)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(request.remoteAddress)

  private def generateSessionId(): String =
    generateToken()

  private def tokenKey(tokenHash: String): String =
    s"auth:refresh:token:$tokenHash"

  private def key(token: String): String =
    tokenKey(sha256(token))

  private def usedKey(tokenHash: String): String =
    s"auth:refresh:used:$tokenHash"

  private def sessionKey(sessionId: String): String =
    s"auth:refresh:session:$sessionId"

  private def sessionRevokedKey(sessionId: String): String =
    s"auth:session:revoked:$sessionId"

  private def generateToken(): String = {
    val bytes = new Array[Byte](48)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  private def sha256(value: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(value.getBytes(StandardCharsets.UTF_8)).map("%02x".format(_)).mkString
  }
}

object RefreshTokenService {
  sealed trait RotateResult
  final case class Rotated(
    loginInfo: LoginInfo,
    refreshToken: String,
    metadata: RefreshSessionMetadata
  ) extends RotateResult
  case object InvalidToken extends RotateResult
  case object ReuseDetected extends RotateResult

  final case class IssuedRefreshToken(
    token: String,
    metadata: RefreshSessionMetadata
  )

  final case class RefreshTokenRecord(
    providerId: String,
    providerKey: String,
    sessionId: String,
    metadata: RefreshSessionMetadata
  ) {
    def loginInfo: LoginInfo = LoginInfo(providerId, providerKey)
  }

  object RefreshTokenRecord {
    implicit val format: OFormat[RefreshTokenRecord] = Json.format[RefreshTokenRecord]
  }
}
