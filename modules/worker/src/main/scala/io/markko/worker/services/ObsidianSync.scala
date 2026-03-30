package io.markko.worker.services

import com.typesafe.scalalogging.LazyLogging
import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser.{decode => jsonDecode}
import io.circe.syntax._

import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatter
import scala.collection.JavaConverters._

/**
 * Synchronizes parsed content to the Obsidian vault as .md files.
 * Supports create, update, and delete operations with delta tracking via .markko-meta.json.
 *
 * Vault structure:
 * ~/vault/Markko/
 * ├── _index.md
 * ├── .markko-meta.json       ← linkId ↔ file path mapping
 * ├── by-tag/
 * │   └── scala.md
 * └── collections/
 *     └── Work/
 *         ├── pekko-docs.md
 *         └── assets/
 */
class ObsidianSync(vaultBasePath: String, allowAbsoluteExportPaths: Boolean = false) extends LazyLogging {

  private val basePath = Paths.get(vaultBasePath)
  private val metaFile = basePath.resolve(".markko-meta.json")

  // --- Meta tracking ---

  case class NoteMeta(linkId: Long, path: String, slug: String, collection: String, updatedAt: String)
  implicit val noteMetaEncoder: Encoder[NoteMeta] = deriveEncoder[NoteMeta]
  implicit val noteMetaDecoder: Decoder[NoteMeta] = deriveDecoder[NoteMeta]

  case class VaultMeta(notes: Map[String, NoteMeta]) // key = linkId as string
  implicit val vaultMetaEncoder: Encoder[VaultMeta] = deriveEncoder[VaultMeta]
  implicit val vaultMetaDecoder: Decoder[VaultMeta] = deriveDecoder[VaultMeta]

  private def loadMeta(): VaultMeta = {
    if (Files.exists(metaFile)) {
      val json = new String(Files.readAllBytes(metaFile), StandardCharsets.UTF_8)
      jsonDecode[VaultMeta](json).getOrElse(VaultMeta(Map.empty))
    } else {
      VaultMeta(Map.empty)
    }
  }

  private def saveMeta(meta: VaultMeta): Unit = {
    Files.createDirectories(basePath)
    Files.write(metaFile, meta.asJson.spaces2.getBytes(StandardCharsets.UTF_8))
  }

  // --- Write / Update ---

  /** Write or update an Obsidian note in the vault */
  def writeNote(
    title:           String,
    url:             String,
    tags:            List[String],
    collection:      String,
    exportDirectory: Option[String],
    exportFileName:  Option[String],
    contentMd:       String,
    readingTime:     Int,
    relatedLinks:    List[String],
    images:          Map[String, String],
    linkId:          Long
  ): Path = {
    val collectionName = defaultCollectionDirectory(collection)
    val noteDir        = resolveNoteDirectory(exportDirectory, collectionName)
    val slug           = sanitizeFileStem(exportFileName.getOrElse(title))
    val notePath = noteDir.resolve(s"$slug.md")

    Files.createDirectories(noteDir)

    // Check if we're updating an existing note at a different path
    val meta = loadMeta()
    meta.notes.get(linkId.toString).foreach { oldMeta =>
      val oldPath = Paths.get(oldMeta.path)
      if (Files.exists(oldPath) && oldPath != notePath) {
        // Collection or title changed — remove old file
        Files.delete(oldPath)
        logger.info(s"Deleted old note at $oldPath (moved to $notePath)")
      }
    }

    // Build the note
    val now     = Instant.now()
    val tagList = tags.map(t => s""""$t"""").mkString("[", ", ", "]")

    val frontmatter =
      s"""---
         |title: "$title"
         |url: "$url"
         |tags: $tagList
         |collection: $collectionName
         |saved_at: ${DateTimeFormatter.ISO_INSTANT.format(now)}
         |parsed_at: ${DateTimeFormatter.ISO_INSTANT.format(now)}
         |reading_time: ${readingTime}min
         |markko_id: $linkId
         |---""".stripMargin

    // Replace image URLs with local paths
    var processedContent = contentMd
    images.foreach { case (originalUrl, localFilename) =>
      processedContent = processedContent.replace(originalUrl, s"assets/$localFilename")
    }

    // Build Related section with wikilinks
    val relatedSection = if (relatedLinks.nonEmpty) {
      val links = relatedLinks.map(r => s"[[$r]]").mkString("\n")
      s"\n\n## Related\n$links\n"
    } else ""

    val fullNote =
      s"""$frontmatter
         |
         |# $title
         |
         |$processedContent
         |$relatedSection""".stripMargin

    Files.write(notePath, fullNote.getBytes(StandardCharsets.UTF_8))
    logger.info(s"Wrote Obsidian note: $notePath")

    // Update meta
    val updatedMeta = meta.copy(
      notes = meta.notes + (linkId.toString -> NoteMeta(
        linkId     = linkId,
        path       = notePath.toString,
        slug       = slug,
        collection = collectionName,
        updatedAt  = DateTimeFormatter.ISO_INSTANT.format(now)
      ))
    )
    saveMeta(updatedMeta)

    // Rebuild the main index
    rebuildIndex()

    notePath
  }

  // --- Delete ---

  /** Delete a note from the vault by its file path */
  def deleteNote(filePath: String): Unit = {
    val path = Paths.get(filePath)
    if (Files.exists(path)) {
      Files.delete(path)
      logger.info(s"Deleted note: $path")

      // Clean up empty parent directory
      val parent = path.getParent
      if (parent != null && Files.isDirectory(parent)) {
        val remaining = Files.list(parent).count()
        if (remaining == 0) {
          Files.delete(parent)
          logger.debug(s"Removed empty directory: $parent")
        }
      }
    }

    // Remove from meta
    val meta = loadMeta()
    val linkIdOpt = meta.notes.find(_._2.path == filePath).map(_._1)
    linkIdOpt.foreach { linkId =>
      saveMeta(meta.copy(notes = meta.notes - linkId))
    }

    rebuildIndex()
  }

  /** Delete a note by linkId */
  def deleteByLinkId(linkId: Long): Unit = {
    val meta = loadMeta()
    meta.notes.get(linkId.toString).foreach { noteMeta =>
      deleteNote(noteMeta.path)

      // Also clean up assets
      val assetsDir = Paths.get(noteMeta.path).getParent.resolve("assets")
      if (Files.exists(assetsDir)) {
        Files.list(assetsDir).iterator().asScala
          .filter(_.getFileName.toString.startsWith(s"${linkId}_"))
          .foreach(Files.delete)
      }
    }
  }

  // --- Index ---

  /** Rebuild _index.md with all links and backlinks graph */
  def rebuildIndex(backlinks: Map[String, List[String]] = Map.empty): Unit = {
    val indexPath = basePath.resolve("_index.md")
    val meta = loadMeta()

    if (meta.notes.isEmpty) return

    // Group by collection
    val byCollection = meta.notes.values.toList
      .groupBy(_.collection)
      .toList.sortBy(_._1)

    val sections = byCollection.map { case (collection, notes) =>
      val links = notes.sortBy(_.slug).map { note =>
        s"- [[${note.slug}]] — `${note.collection}`"
      }.mkString("\n")
      s"## 📁 $collection\n\n$links"
    }.mkString("\n\n")

    // Build backlinks graph section
    val graphSection = if (backlinks.nonEmpty) {
      val graphLinks = backlinks.toList.sortBy(_._1).flatMap { case (target, sources) =>
        sources.map { source =>
          s"- [[$source]] → [[$target]]"
        }
      }.mkString("\n")

      // Mermaid graph for Obsidian
      val mermaidNodes = backlinks.flatMap { case (target, sources) =>
        sources.map(s => s"""    $s --> $target""")
      }.mkString("\n")

      s"""
         |## 🔗 Backlinks Graph
         |
         |$graphLinks
         |
         |```mermaid
         |graph LR
         |$mermaidNodes
         |```
         |""".stripMargin
    } else ""

    val indexContent =
      s"""---
         |title: Markko — All Links
         |total: ${meta.notes.size}
         |updated: ${DateTimeFormatter.ISO_INSTANT.format(Instant.now())}
         |---
         |
         |# 📚 All Saved Links (${meta.notes.size})
         |
         |$sections
         |$graphSection
         |""".stripMargin

    Files.createDirectories(basePath)
    Files.write(indexPath, indexContent.getBytes(StandardCharsets.UTF_8))
  }

  private def slugify(s: String): String =
    s.toLowerCase
      .replaceAll("[^a-z0-9\\s-]", "")
      .replaceAll("\\s+", "-")
      .replaceAll("-{2,}", "-")
      .stripPrefix("-")
      .stripSuffix("-")

  private def sanitizeFileStem(value: String): String = {
    val slug = slugify(value.trim.replaceFirst("(?i)\\.md$", "")).take(80)
    if (slug.nonEmpty) slug else "untitled"
  }

  private def defaultCollectionDirectory(collection: String): String =
    normalizeDirectory(Option(collection)).getOrElse("unsorted")

  private def resolveNoteDirectory(exportDirectory: Option[String], fallbackCollection: String): Path =
    exportDirectory
      .flatMap(resolveExplicitDirectory)
      .getOrElse(basePath.resolve("collections").resolve(fallbackCollection))

  private def resolveExplicitDirectory(rawDirectory: String): Option[Path] = {
    val trimmed = Option(rawDirectory).map(_.trim).getOrElse("")
    if (trimmed.isEmpty) {
      None
    } else {
      val rawPath = Paths.get(trimmed)
      if (rawPath.isAbsolute && allowAbsoluteExportPaths) {
        Some(rawPath.normalize())
      } else {
        normalizeDirectory(Option(trimmed)).map(path => basePath.resolve("collections").resolve(path))
      }
    }
  }

  private def normalizeDirectory(value: Option[String]): Option[String] =
    value.flatMap { raw =>
      val normalized = raw
        .split("[/\\\\]+")
        .iterator
        .map(_.trim)
        .filter(segment => segment.nonEmpty && segment != "." && segment != "..")
        .map(segment => slugify(segment).take(80))
        .filter(_.nonEmpty)
        .mkString("/")

      Option(normalized).filter(_.nonEmpty)
    }
}
