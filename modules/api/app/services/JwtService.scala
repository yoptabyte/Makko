package services

import javax.inject.{Inject, Singleton}
import pdi.jwt.{Jwt, JwtAlgorithm}
import play.api.Configuration
import play.silhouette.api.crypto.AuthenticatorEncoder
import play.silhouette.api.util.{Clock, RequestPart}
import play.silhouette.impl.authenticators.{JWTAuthenticator, JWTAuthenticatorSettings}

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Try

@Singleton
class JwtService @Inject()(
  config: Configuration,
  clock: Clock
) extends AuthenticatorEncoder {

  private val jwt = Jwt(java.time.Clock.systemUTC())
  private val algorithm = JwtAlgorithm.HS256
  private val algorithms = Seq(algorithm)

  val settings: JWTAuthenticatorSettings = JWTAuthenticatorSettings(
    fieldName = config.getOptional[String]("markko.jwt.field-name").getOrElse("token"),
    requestParts = Some(Seq(RequestPart.Headers, RequestPart.QueryString)),
    issuerClaim = config.getOptional[String]("markko.jwt.issuer").getOrElse("markko-api"),
    authenticatorIdleTimeout = None,
    authenticatorExpiry = Duration(
      config.getOptional[String]("markko.jwt.authenticator-expiry").getOrElse("24 hours")
    ).asInstanceOf[FiniteDuration],
    sharedSecret = config.getOptional[String]("markko.jwt.shared-secret")
      .getOrElse(config.get[String]("play.http.secret.key"))
  )

  override def encode(value: String): String =
    jwt.encode(value, settings.sharedSecret, algorithm)

  override def decode(value: String): String =
    jwt.decodeRaw(value, settings.sharedSecret, algorithms).toEither.fold(
      ex => throw new IllegalArgumentException(s"Failed to decode JWT: ${ex.getMessage}", ex),
      identity
    )

  def unserialize(token: String): Try[JWTAuthenticator] = {
    implicit val silhouetteClock: Option[Clock] = Some(clock)
    JWTAuthenticator.unserialize(token, this, settings)
  }
}
