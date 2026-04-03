package io.markko.shared.domain

import java.time.Instant
import io.circe._
import io.circe.generic.semiauto._
import play.api.libs.json.{Format, Json, OFormat}

case class User(
  id: Option[Long],
  email: String,
  name: String,
  password: Option[String] = None,
  createdAt: Option[Instant] = None,
  updatedAt: Option[Instant] = None
)

object User {
  implicit val encoder: Encoder[User] = deriveEncoder[User]
  implicit val decoder: Decoder[User] = deriveDecoder[User]
}

case class UserCreate(
  email: String,
  name: String,
  password: String
)

object UserCreate {
  implicit val format: Format[UserCreate] = Json.format[UserCreate]
  implicit val encoder: Encoder[UserCreate] = deriveEncoder[UserCreate]
  implicit val decoder: Decoder[UserCreate] = deriveDecoder[UserCreate]
}

case class UserLogin(
  email: String,
  password: String
)

object UserLogin {
  implicit val format: Format[UserLogin] = Json.format[UserLogin]
  implicit val encoder: Encoder[UserLogin] = deriveEncoder[UserLogin]
  implicit val decoder: Decoder[UserLogin] = deriveDecoder[UserLogin]
}

case class RefreshTokenRequest(
  refreshToken: String
)

object RefreshTokenRequest {
  implicit val format: Format[RefreshTokenRequest] = Json.format[RefreshTokenRequest]
  implicit val encoder: Encoder[RefreshTokenRequest] = deriveEncoder[RefreshTokenRequest]
  implicit val decoder: Decoder[RefreshTokenRequest] = deriveDecoder[RefreshTokenRequest]
}

case class LogoutRequest(
  refreshToken: Option[String]
)

object LogoutRequest {
  implicit val format: Format[LogoutRequest] = Json.format[LogoutRequest]
  implicit val encoder: Encoder[LogoutRequest] = deriveEncoder[LogoutRequest]
  implicit val decoder: Decoder[LogoutRequest] = deriveDecoder[LogoutRequest]
}

case class RefreshSessionMetadata(
  sessionId: String,
  ipAddress: String,
  userAgent: Option[String],
  createdAt: Instant,
  lastRefreshedAt: Option[Instant]
)

object RefreshSessionMetadata {
  implicit val instantReads: play.api.libs.json.Reads[Instant] =
    play.api.libs.json.Reads.DefaultInstantReads
  implicit val instantWrites: play.api.libs.json.Writes[Instant] =
    play.api.libs.json.Writes.DefaultInstantWrites
  implicit val format: OFormat[RefreshSessionMetadata] = Json.format[RefreshSessionMetadata]
  implicit val encoder: Encoder[RefreshSessionMetadata] = deriveEncoder[RefreshSessionMetadata]
  implicit val decoder: Decoder[RefreshSessionMetadata] = deriveDecoder[RefreshSessionMetadata]
}

case class UserResponse(
  id: Long,
  email: String,
  name: String,
  createdAt: Instant,
  updatedAt: Instant
)

object UserResponse {
  implicit val instantReads: play.api.libs.json.Reads[Instant] =
    play.api.libs.json.Reads.DefaultInstantReads
  implicit val instantWrites: play.api.libs.json.Writes[Instant] =
    play.api.libs.json.Writes.DefaultInstantWrites
  implicit val format: play.api.libs.json.OFormat[UserResponse] = Json.format[UserResponse]
  implicit val encoder: Encoder[UserResponse] = deriveEncoder[UserResponse]
  implicit val decoder: Decoder[UserResponse] = deriveDecoder[UserResponse]

  def from(user: User): Option[UserResponse] = user.id.map { uid =>
    UserResponse(
      id = uid,
      email = user.email,
      name = user.name,
      createdAt = user.createdAt.getOrElse(Instant.now()),
      updatedAt = user.updatedAt.getOrElse(Instant.now())
    )
  }
}

case class AuthTokenResponse(
  accessToken: String,
  refreshToken: String,
  tokenType: String,
  user: UserResponse,
  session: RefreshSessionMetadata
)

object AuthTokenResponse {
  implicit val instantReads: play.api.libs.json.Reads[Instant] =
    play.api.libs.json.Reads.DefaultInstantReads
  implicit val instantWrites: play.api.libs.json.Writes[Instant] =
    play.api.libs.json.Writes.DefaultInstantWrites
  implicit val format: play.api.libs.json.OFormat[AuthTokenResponse] = Json.format[AuthTokenResponse]
  implicit val encoder: Encoder[AuthTokenResponse] = deriveEncoder[AuthTokenResponse]
  implicit val decoder: Decoder[AuthTokenResponse] = deriveDecoder[AuthTokenResponse]
}
