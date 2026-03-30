package io.markko.worker.actors

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import io.lettuce.core.RedisClient
import io.markko.shared.elasticsearch.ElasticsearchSupport
import io.markko.shared.redis.{RedisConfigSupport, RedisKeys}
import io.markko.worker.services.{HtmlToMarkdown, ImageDownloader}

import cats.effect._
import cats.effect.unsafe.implicits.global
import doobie._
import doobie.implicits._
import doobie.implicits.javasql._
import doobie.hikari.HikariTransactor
import doobie.util.meta.Meta

import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.util.{Try, Success, Failure}

/**
 * Worker actor responsible ONLY for parsing:
 * fetch HTML → convert to Markdown → download images → update MySQL → index ES → Redis pub.
 *
 * Vault export is handled separately by ExporterWorker.
 */
object ParserWorker extends LazyLogging {

  sealed trait Command
  case class Parse(linkId: Long, replyTo: ActorRef[ParserSupervisor.Command]) extends Command

  implicit val instantMeta: Meta[Instant] =
    Meta[java.sql.Timestamp].imap(_.toInstant)(java.sql.Timestamp.from)

  def apply(config: Config): Behavior[Command] = {
    Behaviors.setup { context =>
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

      val htmlToMd      = new HtmlToMarkdown()
      val imgDownloader = new ImageDownloader(config)(context.system)

      active(transactor, htmlToMd, imgDownloader, config)
    }
  }

  private def active(
    xa:            Transactor[IO],
    htmlToMd:      HtmlToMarkdown,
    imgDownloader: ImageDownloader,
    config:        Config
  ): Behavior[Command] = {
    Behaviors.receive { (context, message) =>
      implicit val ec: ExecutionContext = context.executionContext

      message match {
        case Parse(linkId, replyTo) =>
          logger.info(s"Starting parse for link $linkId")

          // Mark as parsing
          sql"UPDATE links SET status = 'parsing' WHERE id = $linkId"
            .update.run.transact(xa).unsafeRunSync()

          Try {
            // 1. Fetch link URL from DB
            val (url, userId, collectionSlug, savedAt) = sql"""
              SELECT l.url, l.user_id, COALESCE(c.slug, 'unsorted'), l.saved_at
              FROM links l
              LEFT JOIN collections c ON c.id = l.collection_id
              WHERE l.id = $linkId
            """.query[(String, Long, String, Instant)].unique.transact(xa).unsafeRunSync()

            // 2. Fetch the HTML page
            val html = fetchPage(url)
            logger.info(s"Fetched ${html.length} bytes from $url")

            // 3. Convert HTML to Markdown + extract metadata
            val parseResult = htmlToMd.convert(html, url)
            logger.info(s"Converted to markdown: '${parseResult.title}' (${parseResult.content.length} chars)")

            // 4. Download images
            val images = imgDownloader.downloadAll(html, linkId, collectionSlug)
            logger.info(s"Downloaded ${images.size} images")

            // 5. Calculate reading time (~200 words/min)
            val wordCount   = parseResult.content.split("\\s+").length
            val readingTime = Math.max(1, wordCount / 200)

            // 6. Update DB with parsed content
            val title   = parseResult.title
            val markdown = parseResult.content
            val description = parseResult.description
            val ogImage = parseResult.ogImage

            sql"""UPDATE links
                  SET title = $title, content_md = $markdown, reading_time_min = $readingTime,
                      status = 'parsed', parsed_at = NOW()
                  WHERE id = $linkId"""
              .update.run.transact(xa).unsafeRunSync()

            // 7. Index in Elasticsearch
            val tags = sql"""SELECT t.name FROM tags t
                             JOIN link_tags lt ON lt.tag_id = t.id
                             WHERE lt.link_id = $linkId"""
              .query[String].to[List].transact(xa).unsafeRunSync()

            indexInElasticsearch(linkId, userId, url, title, markdown, tags, collectionSlug, savedAt, config)
            sql"UPDATE links SET indexed_at = NOW() WHERE id = $linkId"
              .update.run.transact(xa).unsafeRunSync()

            // 8. Publish parsed event to Redis
            publishParsedEvent(linkId, config)

            logger.info(s"Parse pipeline complete for link $linkId")
          } match {
            case Success(_) =>
              replyTo ! ParserSupervisor.ParseComplete(linkId, success = true)
            case Failure(ex) =>
              logger.error(s"Parse failed for link $linkId", ex)
              sql"UPDATE links SET status = 'failed' WHERE id = $linkId"
                .update.run.transact(xa).unsafeRunSync()
              replyTo ! ParserSupervisor.ParseComplete(linkId, success = false)
          }

          Behaviors.same
      }
    }
  }

  /** Fetch a page via simple HTTP */
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

  /** Index document in Elasticsearch */
  private def indexInElasticsearch(
    linkId: Long,
    userId: Long,
    url: String,
    title: String,
    content: String,
    tags: List[String],
    collection: String,
    savedAt: Instant,
    config: Config
  ): Unit = {
    import com.sksamuel.elastic4s.ElasticDsl._
    val client = ElasticsearchSupport.client(config)

    try {
      client.execute {
        indexInto("markko-links")
          .id(linkId.toString)
          .fields(
            "title"   -> title,
            "content" -> content,
            "url"     -> url,
            "tags"    -> tags,
            "collection" -> collection,
            "userId"  -> userId,
            "savedAt" -> savedAt.toString
          )
      }.await
    } finally {
      client.close()
    }
  }

  /** Publish parsed event via Redis */
  private def publishParsedEvent(linkId: Long, config: Config): Unit = {
    val client = RedisClient.create(RedisConfigSupport.connectionUri(config))
    val connection = client.connect()

    try {
      connection.sync().publish(RedisKeys.ParsedEventsChannel, linkId.toString)
      ()
    } finally {
      connection.close()
      client.shutdown()
    }
  }
}
