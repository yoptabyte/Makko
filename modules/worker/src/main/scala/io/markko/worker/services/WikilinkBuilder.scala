package io.markko.worker.services

import cats.effect._
import cats.effect.unsafe.implicits.global
import doobie._
import doobie.implicits._
import com.typesafe.scalalogging.LazyLogging

/**
 * Builds Obsidian [[wikilinks]] by finding related links.
 * Uses multiple signals to rank relevance:
 * 1. Shared tags (strongest signal)
 * 2. Same collection
 * 3. Content similarity via ES more-like-this (future)
 *
 * Also builds a full backlinks map for the vault graph.
 */
class WikilinkBuilder(xa: Transactor[IO]) extends LazyLogging {

  /**
   * Find top-5 related links by shared tag count.
   * Returns list of slugified titles suitable for Obsidian wikilinks.
   */
  def findRelated(linkId: Long): List[String] = {
    sql"""
      SELECT l.title FROM links l
      JOIN link_tags lt ON lt.link_id = l.id
      WHERE lt.tag_id IN (SELECT tag_id FROM link_tags WHERE link_id = $linkId)
        AND l.id != $linkId
        AND l.status = 'parsed'
        AND l.title IS NOT NULL
      GROUP BY l.id, l.title
      ORDER BY COUNT(*) DESC
      LIMIT 5
    """.query[String]
      .to[List]
      .transact(xa)
      .unsafeRunSync()
      .map(slugify)
  }

  /**
   * Find related links using multiple signals:
   * shared tags + same collection, with weighted scoring.
   */
  def findRelatedWeighted(linkId: Long): List[RelatedLink] = {
    sql"""
      SELECT
        l.id,
        l.title,
        COALESCE(c.slug, 'unsorted') as collection,
        (
          SELECT COUNT(*) FROM link_tags lt1
          JOIN link_tags lt2 ON lt1.tag_id = lt2.tag_id
          WHERE lt1.link_id = l.id AND lt2.link_id = $linkId
        ) as shared_tags,
        CASE WHEN l.collection_id = (SELECT collection_id FROM links WHERE id = $linkId)
             THEN 1 ELSE 0 END as same_collection
      FROM links l
      LEFT JOIN collections c ON c.id = l.collection_id
      WHERE l.id != $linkId
        AND l.status = 'parsed'
        AND l.title IS NOT NULL
        AND (
          l.id IN (
            SELECT lt1.link_id FROM link_tags lt1
            WHERE lt1.tag_id IN (SELECT tag_id FROM link_tags WHERE link_id = $linkId)
          )
          OR l.collection_id = (SELECT collection_id FROM links WHERE id = $linkId)
        )
      ORDER BY (shared_tags * 3 + same_collection * 1) DESC
      LIMIT 8
    """.query[(Long, String, String, Int, Int)]
      .to[List]
      .transact(xa)
      .unsafeRunSync()
      .map { case (id, title, collection, sharedTags, sameCol) =>
        RelatedLink(
          linkId       = id,
          title        = title,
          slug         = slugify(title),
          collection   = collection,
          sharedTags   = sharedTags,
          sameCollection = sameCol == 1,
          score        = sharedTags * 3 + sameCol
        )
      }
  }

  /**
   * Build a full backlinks map: for each link, which other links reference it.
   * Returns Map[slugifiedTitle → List[slugifiedTitle]] of backlinks.
   */
  def buildBacklinksMap(): Map[String, List[String]] = {
    // Get all parsed links with their related links
    val allLinks: List[(Long, String)] = sql"""
      SELECT id, title FROM links
      WHERE status = 'parsed' AND title IS NOT NULL
    """.query[(Long, String)].to[List].transact(xa).unsafeRunSync()

    val backlinks = scala.collection.mutable.Map[String, List[String]]()

    allLinks.foreach { case (linkId, title) =>
      val slug = slugify(title)
      val related = findRelated(linkId)

      // For each related link, add this link as a backlink
      related.foreach { relatedSlug =>
        val existing = backlinks.getOrElse(relatedSlug, Nil)
        if (!existing.contains(slug)) {
          backlinks(relatedSlug) = slug :: existing
        }
      }
    }

    backlinks.toMap
  }

  /**
   * Get backlinks for a specific link (who links to this note).
   */
  def getBacklinks(linkId: Long): List[String] = {
    val title = sql"SELECT title FROM links WHERE id = $linkId"
      .query[String].option.transact(xa).unsafeRunSync()

    title match {
      case None => Nil
      case Some(t) =>
        val slug = slugify(t)
        // Find links that have this link in their top-5 related
        sql"""
          SELECT DISTINCT l2.title
          FROM links l2
          JOIN link_tags lt2 ON lt2.link_id = l2.id
          WHERE lt2.tag_id IN (SELECT tag_id FROM link_tags WHERE link_id = $linkId)
            AND l2.id != $linkId
            AND l2.status = 'parsed'
            AND l2.title IS NOT NULL
          GROUP BY l2.id, l2.title
          HAVING COUNT(*) >= 2
          ORDER BY COUNT(*) DESC
          LIMIT 10
        """.query[String]
          .to[List]
          .transact(xa)
          .unsafeRunSync()
          .map(slugify)
    }
  }

  private def slugify(s: String): String =
    s.toLowerCase
      .replaceAll("[^a-z0-9\\s-]", "")
      .replaceAll("\\s+", "-")
      .take(80)
}

/** A related link with scoring details */
case class RelatedLink(
  linkId:         Long,
  title:          String,
  slug:           String,
  collection:     String,
  sharedTags:     Int,
  sameCollection: Boolean,
  score:          Int
)
