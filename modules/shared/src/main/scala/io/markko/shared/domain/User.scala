package io.markko.shared.domain

import java.time.LocalDateTime
import io.circe._
import io.circe.generic.semiauto._
import play.api.libs.json.{Format, Json}

case class User(
  id: Option[Long],
  email: String,
  name: String,
  password: Option[String] = None,
  createdAt: Option[LocalDateTime] = None,
  updatedAt: Option[LocalDateTime] = None
)

object User {
  implicit val format: Format[User] = Json.format[User]
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
  createdAt: LocalDateTime,
  lastRefreshedAt: Option[LocalDateTime]
)

object RefreshSessionMetadata {
  implicit val format: Format[RefreshSessionMetadata] = Json.format[RefreshSessionMetadata]
  implicit val encoder: Encoder[RefreshSessionMetadata] = deriveEncoder[RefreshSessionMetadata]
  implicit val decoder: Decoder[RefreshSessionMetadata] = deriveDecoder[RefreshSessionMetadata]
}

case class UserResponse(
  id: Long,
  email: String,
  name: String,
  createdAt: LocalDateTime,
  updatedAt: LocalDateTime
)

object UserResponse {
  implicit val format: Format[UserResponse] = Json.format[UserResponse]
  implicit val encoder: Encoder[UserResponse] = deriveEncoder[UserResponse]
  implicit val decoder: Decoder[UserResponse] = deriveDecoder[UserResponse]
  
  def from(user: User): UserResponse = UserResponse(
    id = user.id.getOrElse(0L),
    email = user.email,
    name = user.name,
    createdAt = user.createdAt.getOrElse(LocalDateTime.now()),
    updatedAt = user.updatedAt.getOrElse(LocalDateTime.now())
  )
}

case class AuthTokenResponse(
  token: String,
  accessToken: String,
  refreshToken: String,
  tokenType: String,
  user: UserResponse,
  session: RefreshSessionMetadata
)

object AuthTokenResponse {
  implicit val format: Format[AuthTokenResponse] = Json.format[AuthTokenResponse]
  implicit val encoder: Encoder[AuthTokenResponse] = deriveEncoder[AuthTokenResponse]
  implicit val decoder: Decoder[AuthTokenResponse] = deriveDecoder[AuthTokenResponse]
}
