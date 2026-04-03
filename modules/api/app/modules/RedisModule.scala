package modules

import javax.inject._
import com.google.inject.{AbstractModule, Provides}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.RedisCommands
import dev.profunktor.redis4cats.effect.Log.Stdout._
import io.markko.shared.redis.RedisConfigSupport
import play.api.Configuration

class RedisModule extends AbstractModule {

  override def configure(): Unit = ()

  @Provides
  @Singleton
  def redisCommands(config: Configuration): RedisCommands[IO, String, String] = {
    val uri = RedisConfigSupport.connectionUri(config.underlying)
    Redis[IO].utf8(uri).allocated.unsafeRunSync()._1
  }
}
