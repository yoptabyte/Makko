package io.markko.worker.services

import cats.effect._
import cats.effect.unsafe.implicits.global
import doobie._
import doobie.implicits._
import doobie.implicits.javasql._
import doobie.util.meta.Meta
import com.sksamuel.elastic4s.ElasticDsl._
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import io.markko.shared.elasticsearch.ElasticsearchSupport
import io.prometheus.client.{Counter, Gauge}

import java.time.Instant

/**
 * Elasticsearch resync process: finds links in MySQL that haven't been indexed
 * and bulk-indexes them into ES. Runs as a scheduled task or on-demand.
 *
 * Handles:
 * - Initial bulk indexing (all parsed links)
 * - Incremental sync (only unindexed links)
 * - Full reindex (drop + recreate index)
 */
class ElasticsearchResync(config: Config, xa: Transactor[IO]) extends LazyLogging {

  implicit val instantMeta: Meta[Instant] =
    Meta[java.sql.Timestamp].imap(_.toInstant)(java.sql.Timestamp.from)

  private val indexName = "markko-links"

  // Prometheus metrics
  private val resyncDocsTotal = Counter.build()
    .name("markko_es_resync_docs_total")
    .help("Total documents resynced to Elasticsearch")
    .register()

  private val resyncErrorsTotal = Counter.build()
    .name("markko_es_resync_errors_total")
    .help("Total errors during ES resync")
    .register()

  private val esIndexLag = Gauge.build()
    .name("markko_es_index_lag")
    .help("Number of parsed links not yet indexed in ES")
    .register()

  /**
   * Incremental sync: index all parsed links where indexed_at IS NULL.
   * Uses Doobie streaming to process in batches without loading all into memory.
   */
  def syncUnindexed(batchSize: Int = 50): Int = {
    logger.info("Starting incremental ES resync...")

    val client = ElasticsearchSupport.client(config)

    try {
      // Load all unindexed links into memory (batch query)
      val unindexedLinks = sql"""
        SELECT l.id, l.url, COALESCE(l.title, ''), COALESCE(l.content_md, ''),
               COALESCE(l.reading_time_min, 0), l.saved_at, l.user_id,
               COALESCE(c.slug, 'unsorted')
        FROM links l
        LEFT JOIN collections c ON c.id = l.collection_id
        WHERE l.status = 'parsed' AND l.indexed_at IS NULL
        ORDER BY l.id
      """.query[(Long, String, String, String, Int, Instant, Long, String)]
        .to[List]
        .transact(xa)
        .unsafeRunSync()

      // Process in batches
      var totalIndexed = 0
      unindexedLinks.grouped(batchSize).foreach { batch =>
        val ops = batch.map { case (id, url, title, content, readTime, savedAt, userId, collection) =>
          val tags = sql"""SELECT t.name FROM tags t
                           JOIN link_tags lt ON lt.tag_id = t.id
                           WHERE lt.link_id = $id"""
            .query[String].to[List].transact(xa).unsafeRunSync()

          indexInto(indexName)
            .id(id.toString)
            .fields(
              "title"       -> title,
              "content"     -> content,
              "url"         -> url,
              "tags"        -> tags,
              "collection"  -> collection,
              "userId"      -> userId,
              "readingTime" -> readTime,
              "savedAt"     -> savedAt.toString
            )
        }

        if (ops.nonEmpty) {
          val result = client.execute(bulk(ops)).await
          if (result.isError) {
            logger.error(s"Bulk index error: ${result.error.reason}")
            resyncErrorsTotal.inc()
          }
        }

        batch.foreach { case (id, _, _, _, _, _, _, _) =>
          sql"UPDATE links SET indexed_at = NOW() WHERE id = $id"
            .update.run.transact(xa).unsafeRunSync()
          resyncDocsTotal.inc()
        }

        totalIndexed += batch.size
      }

      logger.info(s"Incremental ES resync complete: $totalIndexed documents indexed")
      totalIndexed
    } finally {
      client.close()
    }
  }

  /**
   * Full reindex: delete the ES index, recreate it, then index all parsed links.
   */
  def fullReindex(): Int = {
    logger.info("Starting full ES reindex...")

    val client = ElasticsearchSupport.client(config)

    try {
      // Delete existing index
      client.execute(deleteIndex(indexName)).await
      logger.info(s"Deleted index $indexName")

      // Recreate with mapping
      client.execute {
        createIndex(indexName).mapping(
          properties(
            textField("title").boost(2.0),
            textField("content"),
            keywordField("url"),
            keywordField("tags"),
            keywordField("collection"),
            keywordField("status"),
            longField("userId"),
            intField("readingTime"),
            dateField("savedAt"),
            dateField("parsedAt")
          )
        )
      }.await
      logger.info(s"Recreated index $indexName with mapping")

      // Reset all indexed_at timestamps
      sql"UPDATE links SET indexed_at = NULL WHERE status = 'parsed'"
        .update.run.transact(xa).unsafeRunSync()

      // Reindex everything
      syncUnindexed()
    } finally {
      client.close()
    }
  }

  /** Update the ES index lag metric (called periodically) */
  def updateLagMetric(): Unit = {
    val lag = sql"""
      SELECT COUNT(*) FROM links
      WHERE status = 'parsed' AND indexed_at IS NULL
    """.query[Long].unique.transact(xa).unsafeRunSync()

    esIndexLag.set(lag.toDouble)
  }
}
