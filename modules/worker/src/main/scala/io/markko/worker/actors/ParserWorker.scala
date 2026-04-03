package io.markko.worker.actors

import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.markko.shared.elasticsearch.ElasticsearchSupport
import io.markko.shared.redis.{RedisConfigSupport, RedisKeys}
import io.markko.worker.services.{HtmlToMarkdown, ImageDownloader, WorkerMetrics}

import cats.effect._
import cats.effect.unsafe.implicits.global
import doobie._
import doobie.implicits._
import doobie.implicits.javasql._
import doobie.hikari.HikariTransactor
import doobie.util.meta.Meta

import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}

object ParserWorker extends LazyLogging {

  sealed trait Command
  case class Parse(linkId: Long, replyTo: ActorRef[ParserSupervisor.Command]) extends Command
  private case class ParseResult(linkId: Long, success: Boolean, replyTo: ActorRef[ParserSupervisor.Command]) extends Command

  implicit val instantMeta: Meta[Instant] =
    Meta[java.sql.Timestamp].imap(_.toInstant)(java.sql.Timestamp.from)

  def apply(config: Config): Behavior[Command] = {
    Behaviors.supervise[Command] {
      Behaviors.setup[Command] { context =>
        implicit val ec: ExecutionContext = context.executionContext

        val dbUrl      = config.getString("markko.database.url")
        val dbUser     = config.getString("markko.database.user")
        val dbPassword = config.getString("markko.database.password")

        val transactor = HikariTransactor.newHikariTransactor[IO](
          driverClassName = "com.mysql.cj.jdbc.Driver",
          url             = dbUrl,
          user            = dbUser,
          pass            = dbPassword,
          connectEC       = ExecutionContext.global
        ).allocated.unsafeRunSync()._1

        val esClient = ElasticsearchSupport.client(config)
        val redisClient = RedisClient.create(RedisConfigSupport.connectionUri(config))
        val redisConnection: StatefulRedisConnection[String, String] = redisClient.connect()

        val htmlToMd      = new HtmlToMarkdown()
        val imgDownloader = new ImageDownloader(config)(context.system)

        context.log.info("ParserWorker initialized with shared ES and Redis clients")

        active(transactor, esClient, redisConnection, htmlToMd, imgDownloader, config)
      }
    }.onFailure[Exception](SupervisorStrategy.restartWithBackoff(1.second, 30.seconds, 0.2))
  }

  private def active(
    xa:              Transactor[IO],
    esClient:        com.sksamuel.elastic4s.ElasticClient,
    redisConnection: StatefulRedisConnection[String, String],
    htmlToMd:        HtmlToMarkdown,
    imgDownloader:   ImageDownloader,
    config:          Config
  ): Behavior[Command] = {
    Behaviors.receive { (context, message) =>
      implicit val ec: ExecutionContext = context.executionContext

      message match {
        case Parse(linkId, replyTo) =>
          logger.info(s"Starting parse for link $linkId")
          WorkerMetrics.parseJobsStarted.inc()

          sql"UPDATE links SET status = 'parsing' WHERE id = $linkId"
            .update.run.transact(xa).unsafeRunSync()

          context.pipeToSelf(scala.concurrent.Future {
            doParse(linkId, xa, esClient, redisConnection, htmlToMd, imgDownloader, config)
          }) {
            case Success(success) => ParseResult(linkId, success, replyTo)
            case Failure(ex) =>
              logger.error(s"Parse pipeline crashed for link $linkId", ex)
              ParseResult(linkId, success = false, replyTo)
          }

          Behaviors.same

        case ParseResult(linkId, success, replyTo) =>
          if (success) {
            logger.info(s"Parse pipeline complete for link $linkId")
            WorkerMetrics.parseJobsCompleted.inc()
          } else {
            logger.warn(s"Parse failed for link $linkId")
            WorkerMetrics.parseJobsFailed.inc()
            sql"UPDATE links SET status = 'failed' WHERE id = $linkId"
              .update.run.transact(xa).unsafeRunSync()
          }
          replyTo ! ParserSupervisor.ParseComplete(linkId, success)
          Behaviors.same
      }
    }
  }

  private def doParse(
    linkId:          Long,
    xa:              Transactor[IO],
    esClient:        com.sksamuel.elastic4s.ElasticClient,
    redisConnection: StatefulRedisConnection[String, String],
    htmlToMd:        HtmlToMarkdown,
    imgDownloader:   ImageDownloader,
    config:          Config
  ): Boolean = {
    import com.sksamuel.elastic4s.ElasticDsl._

    val (url, userId, collectionSlug, savedAt) = sql"""
      SELECT l.url, l.user_id, COALESCE(c.slug, 'unsorted'), l.saved_at
      FROM links l
      LEFT JOIN collections c ON c.id = l.collection_id
      WHERE l.id = $linkId
    """.query[(String, Long, String, Instant)].unique.transact(xa).unsafeRunSync()

    val html = fetchPage(url)
    logger.info(s"Fetched ${html.length} bytes from $url")

    val parseResult = htmlToMd.convert(html, url)
    logger.info(s"Converted to markdown: '${parseResult.title}' (${parseResult.content.length} chars)")

    val images = imgDownloader.downloadAll(html, linkId, collectionSlug)
    logger.info(s"Downloaded ${images.size} images")

    val wordCount   = parseResult.content.split("\\s+").length
    val readingTime = Math.max(1, wordCount / 200)

    val title    = parseResult.title
    val markdown = parseResult.content

    sql"""UPDATE links
          SET title = $title, content_md = $markdown, reading_time_min = $readingTime,
              status = 'parsed', parsed_at = NOW()
          WHERE id = $linkId"""
      .update.run.transact(xa).unsafeRunSync()

    val tags = sql"""SELECT t.name FROM tags t
                     JOIN link_tags lt ON lt.tag_id = t.id
                     WHERE lt.link_id = $linkId"""
      .query[String].to[List].transact(xa).unsafeRunSync()

    esClient.execute {
      indexInto("markko-links")
        .id(linkId.toString)
        .fields(
          "title"   -> title,
          "content" -> markdown,
          "url"     -> url,
          "tags"    -> tags,
          "collection" -> collectionSlug,
          "userId"  -> userId,
          "savedAt" -> savedAt.toString
        )
    }.await

    sql"UPDATE links SET indexed_at = NOW() WHERE id = $linkId"
      .update.run.transact(xa).unsafeRunSync()

    redisConnection.sync().publish(RedisKeys.ParsedEventsChannel, linkId.toString)

    true
  }

  private def fetchPage(url: String): String = {
    import java.net.{HttpURLConnection, URI}
    import scala.io.Source

    val conn = URI.create(url).toURL.openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestProperty("User-Agent", "Markko/1.0 (bookmark parser)")
    conn.setConnectTimeout(10000)
    conn.setReadTimeout(15000)
    conn.setInstanceFollowRedirects(true)

    try {
      val source = Source.fromInputStream(conn.getInputStream, "UTF-8")
      try source.mkString
      finally source.close()
    } finally {
      conn.disconnect()
    }
  }
}
