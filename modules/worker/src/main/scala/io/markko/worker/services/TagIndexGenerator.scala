package io.markko.worker.services

import cats.effect._
import cats.effect.unsafe.implicits.global
import doobie._
import doobie.implicits._
import com.typesafe.scalalogging.LazyLogging

import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatter
import scala.jdk.CollectionConverters._

/**
 * Generates per-tag index files in the Obsidian vault:
 *
 * vault/by-tag/scala.md       — all links tagged #scala
 * vault/by-tag/architecture.md — all links tagged #architecture
 *
 * Each file contains YAML frontmatter and [[wikilinks]] to all notes with that tag.
 */
class TagIndexGenerator(vaultBasePath: String, xa: Transactor[IO]) extends LazyLogging {

  private val basePath  = Paths.get(vaultBasePath)
  private val byTagDir  = basePath.resolve("by-tag")

  /** Rebuild all tag index files from scratch */
  def rebuildAll(): Unit = {
    Files.createDirectories(byTagDir)

    // Fetch all tags with their linked titles
    val tagLinks: List[(String, String, String)] = sql"""
      SELECT t.name, l.title, COALESCE(c.slug, 'unsorted')
      FROM tags t
      JOIN link_tags lt ON lt.tag_id = t.id
      JOIN links l ON l.id = lt.link_id
      LEFT JOIN collections c ON c.id = l.collection_id
      WHERE l.status = 'parsed' AND l.title IS NOT NULL
      ORDER BY t.name, l.title
    """.query[(String, String, String)].to[List].transact(xa).unsafeRunSync()

    // Group by tag name
    val grouped = tagLinks.groupBy(_._1)

    // Clean old tag files
    if (Files.exists(byTagDir)) {
      Files.list(byTagDir).iterator().asScala
        .filter(_.toString.endsWith(".md"))
        .foreach(Files.delete)
    }

    // Generate a .md file per tag
    grouped.foreach { case (tagName, links) =>
      writeTagIndex(tagName, links.map(t => (t._2, t._3)))
    }

    logger.info(s"Rebuilt ${grouped.size} tag index files in by-tag/")
  }

  /** Rebuild index for a single tag */
  def rebuildTag(tagName: String): Unit = {
    Files.createDirectories(byTagDir)

    val links: List[(String, String)] = sql"""
      SELECT l.title, COALESCE(c.slug, 'unsorted')
      FROM links l
      JOIN link_tags lt ON lt.link_id = l.id
      JOIN tags t ON t.id = lt.tag_id
      LEFT JOIN collections c ON c.id = l.collection_id
      WHERE t.name = $tagName AND l.status = 'parsed' AND l.title IS NOT NULL
      ORDER BY l.title
    """.query[(String, String)].to[List].transact(xa).unsafeRunSync()

    if (links.isEmpty) {
      // Remove tag file if no links remain
      val tagFile = byTagDir.resolve(s"${slugify(tagName)}.md")
      if (Files.exists(tagFile)) Files.delete(tagFile)
    } else {
      writeTagIndex(tagName, links)
    }
  }

  private def writeTagIndex(tagName: String, links: List[(String, String)]): Unit = {
    val slug    = slugify(tagName)
    val tagFile = byTagDir.resolve(s"$slug.md")

    val wikilinks = links.map { case (title, collection) =>
      val noteSlug = slugify(title)
      s"- [[$noteSlug|$title]] — `$collection`"
    }.mkString("\n")

    val content =
      s"""---
         |title: "Tag: #$tagName"
         |tag: $tagName
         |count: ${links.size}
         |updated: ${DateTimeFormatter.ISO_INSTANT.format(Instant.now())}
         |---
         |
         |# #$tagName
         |
         |**${links.size} links** with this tag:
         |
         |$wikilinks
         |""".stripMargin

    Files.write(tagFile, content.getBytes(StandardCharsets.UTF_8))
  }

  private def slugify(s: String): String =
    s.toLowerCase
      .replaceAll("[^a-z0-9\\s-]", "")
      .replaceAll("\\s+", "-")
      .take(80)
}
