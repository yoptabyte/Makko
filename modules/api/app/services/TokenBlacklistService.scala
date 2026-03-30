package services

import cats.effect.unsafe.implicits.global
import cats.effect.IO
import dev.profunktor.redis4cats.RedisCommands
import javax.inject.{Inject, Singleton}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.{Duration, Instant}
import scala.concurrent.Future
import scala.concurrent.duration._

@Singleton
class TokenBlacklistService @Inject()(
  redis: RedisCommands[IO, String, String]
) {

  def isBlacklisted(token: String): Future[Boolean] =
    redis.get(key(token)).map(_.isDefined).unsafeToFuture()

  def blacklist(token: String, expiresAt: Instant): Future[Unit] = {
    val ttl = Duration.between(Instant.now(), expiresAt).getSeconds.max(1L)
    redis.setEx(key(token), "1", ttl.seconds).unsafeToFuture()
  }

  private def key(token: String): String =
    s"auth:blacklist:${sha256(token)}"

  private def sha256(value: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(value.getBytes(StandardCharsets.UTF_8)).map("%02x".format(_)).mkString
  }
}
