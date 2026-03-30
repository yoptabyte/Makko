package services

import java.time.Instant
import java.util.UUID
import javax.inject.{Inject, Singleton}
import cats.effect.IO
import dev.profunktor.redis4cats.RedisCommands
import dev.profunktor.redis4cats.effects.{Score, ScoreWithValue, ZRange}
import scala.concurrent.duration._

case class RateLimitConfig(
  windowSizeSeconds: Int,
  maxRequests: Int
)

@Singleton
class RateLimitService @Inject()(redis: RedisCommands[IO, String, String]) {

  def isAllowed(
    key: String, 
    config: RateLimitConfig
  ): IO[Boolean] = {
    val now = Instant.now().toEpochMilli
    val windowStart = now - (config.windowSizeSeconds.toLong * 1000L)
    val member = s"$now:${UUID.randomUUID().toString}"

    for {
      _ <- redis.zRemRangeByScore(key, ZRange(Long.MinValue, windowStart))
      currentCount <- redis.zCount(key, ZRange(windowStart, Long.MaxValue))
      allowed = currentCount < config.maxRequests
      _ <- if (allowed) {
        redis.zAdd(key, None, ScoreWithValue(Score(now.toDouble), member)) *>
        redis.expire(key, config.windowSizeSeconds.seconds)
      } else {
        IO.unit
      }
    } yield allowed
  }
  
  def getRemainingRequests(
    key: String,
    config: RateLimitConfig
  ): IO[Int] = {
    val now = Instant.now().toEpochMilli
    val windowStart = now - (config.windowSizeSeconds.toLong * 1000L)

    for {
      _ <- redis.zRemRangeByScore(key, ZRange(Long.MinValue, windowStart))
      currentCount <- redis.zCount(key, ZRange(windowStart, Long.MaxValue))
      remaining = Math.max(0, config.maxRequests - currentCount.toInt)
    } yield remaining
  }
  
  def getResetTime(
    key: String,
    config: RateLimitConfig
  ): IO[Long] = {
    IO.pure(Instant.now().getEpochSecond + config.windowSizeSeconds.toLong)
  }
}
