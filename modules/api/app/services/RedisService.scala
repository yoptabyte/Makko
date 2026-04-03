package services

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import dev.profunktor.redis4cats.RedisCommands
import io.lettuce.core.RedisClient
import io.lettuce.core.pubsub.{RedisPubSubAdapter, StatefulRedisPubSubConnection}
import io.markko.shared.redis.{RedisConfigSupport, RedisKeys}
import javax.inject._
import play.api.Configuration
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

object RedisService {
  final class Subscription(private val dispose: () => Unit) extends AutoCloseable {
    override def close(): Unit = dispose()
    def unsubscribe(): Unit = close()
  }
}

@Singleton
class RedisService @Inject()(
  config: Configuration,
  redis:  RedisCommands[IO, String, String]
)(implicit ec: ExecutionContext) {

  private val redisUri = RedisConfigSupport.connectionUri(config.underlying)

  private lazy val pubSubClient = RedisClient.create(redisUri)
  private lazy val pubSubConnection: StatefulRedisPubSubConnection[String, String] = pubSubClient.connectPubSub()

  // ==================== Deduplication ====================

  def checkDedup(urlHash: String, userId: Long): Future[Boolean] =
    redis.get(RedisKeys.dedupKey(s"$userId:$urlHash")).map(_.isDefined).unsafeToFuture()

  def setDedup(urlHash: String, userId: Long): Future[Unit] = {
    import scala.concurrent.duration._
    redis.setEx(RedisKeys.dedupKey(s"$userId:$urlHash"), "1", 24.hours).unsafeToFuture()
  }

  // ==================== Parse Queue ====================

  def enqueueParseJob(linkId: Long): Future[Unit] =
    redis.rPush(RedisKeys.ParseQueue, linkId.toString).map(_ => ()).unsafeToFuture()

  def dequeueParseJob(): Future[Option[Long]] =
    redis.lPop(RedisKeys.ParseQueue).map(_.flatMap(s => scala.util.Try(s.toLong).toOption)).unsafeToFuture()

  def queueDepth(): Future[Long] =
    redis.lLen(RedisKeys.ParseQueue).unsafeToFuture()

  // ==================== Export Queue ====================

  def enqueueExportJob(linkId: Long): Future[Unit] =
    redis.rPush(RedisKeys.ExportQueue, linkId.toString).map(_ => ()).unsafeToFuture()

  def enqueueDeleteJob(linkId: Long): Future[Unit] =
    redis.rPush(RedisKeys.DeleteQueue, linkId.toString).map(_ => ()).unsafeToFuture()

  // ==================== Preview Cache ====================

  def cachePreview(linkId: Long, json: JsObject): Future[Unit] = {
    import scala.concurrent.duration._
    redis.setEx(RedisKeys.previewKey(linkId), Json.stringify(json), 1.hour).unsafeToFuture()
  }

  def getCachedPreview(linkId: Long): Future[Option[JsObject]] =
    redis.get(RedisKeys.previewKey(linkId)).map(_.flatMap(s => Json.parse(s).asOpt[JsObject])).unsafeToFuture()

  // ==================== Pub/Sub ====================

  def publishParsedEvent(linkId: Long): Future[Unit] = Future {
    pubSubConnection.sync().publish(RedisKeys.ParsedEventsChannel, linkId.toString)
    ()
  }

  def subscribeParsedEvents(callback: Long => Unit): RedisService.Subscription = {
    val client = RedisClient.create(redisUri)
    val connection = client.connectPubSub()

    val listener = new RedisPubSubAdapter[String, String] {
      override def message(channel: String, message: String): Unit = {
        if (channel == RedisKeys.ParsedEventsChannel) {
          scala.util.Try(message.toLong).toOption.foreach(callback)
        }
      }
    }

    connection.addListener(listener)
    connection.sync().subscribe(RedisKeys.ParsedEventsChannel)

    new RedisService.Subscription(() => {
      try connection.sync().unsubscribe(RedisKeys.ParsedEventsChannel)
      catch { case NonFatal(_) => () }

      try connection.removeListener(listener)
      catch { case NonFatal(_) => () }

      try connection.close()
      catch { case NonFatal(_) => () }

      try client.shutdown()
      catch { case NonFatal(_) => () }
    })
  }
}
