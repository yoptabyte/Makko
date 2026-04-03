package io.markko.worker.services

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.{Source, Sink}
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import io.prometheus.client.{Counter, Gauge}
import org.jsoup.Jsoup

import java.net.{URL, HttpURLConnection}
import java.nio.file.{Path, Paths, Files}
import scala.jdk.CollectionConverters._
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.Try

/**
 * Download images from HTML pages using Pekko Streams with backpressure.
 * Includes Prometheus metrics and size limits.
 */
class ImageDownloader(config: Config)(implicit system: ActorSystem[_]) extends LazyLogging {

  implicit val ec: ExecutionContext = system.executionContext

  private val vaultPath = config.getString("markko.vault.base-path")

  // Max image file size: 10 MB
  private val MaxImageBytes = 10 * 1024 * 1024L

  // --- Prometheus Metrics ---
  private val imagesDownloaded = Counter.build()
    .name("markko_images_downloaded_total")
    .help("Total number of images downloaded successfully")
    .register()

  private val imagesFailed = Counter.build()
    .name("markko_images_failed_total")
    .help("Total number of image downloads that failed")
    .register()

  private val imagesBytesTotal = Counter.build()
    .name("markko_images_bytes_total")
    .help("Total bytes of images downloaded")
    .register()

  private val imagesInFlight = Gauge.build()
    .name("markko_images_in_flight")
    .help("Number of images currently being downloaded")
    .register()

  /**
   * Extract images from HTML and download them to the vault assets directory.
   * Returns a map of original URL → local filename.
   */
  def downloadAll(html: String, linkId: Long, collectionSlug: String): Map[String, String] = {
    val doc = Jsoup.parse(html)
    val imageUrls = doc.select("img[src]").asScala.toList
      .map(_.attr("abs:src"))
      .filter(_.nonEmpty)
      .filter(u => u.startsWith("http://") || u.startsWith("https://"))
      .distinct
      .take(50) // cap at 50 images per page

    if (imageUrls.isEmpty) {
      logger.debug(s"No images found for link $linkId")
      return Map.empty
    }

    // Create assets directory
    val assetsDir = Paths.get(vaultPath, "collections", collectionSlug, "assets")
    Files.createDirectories(assetsDir)

    // Download with backpressure using Pekko Streams + wireTap for metrics
    val resultFuture = Source(imageUrls)
      .mapAsyncUnordered(4) { url =>
        scala.concurrent.Future {
          imagesInFlight.inc()
          try {
            downloadImage(url, assetsDir, linkId) match {
              case Some((filename, bytes)) =>
                imagesDownloaded.inc()
                imagesBytesTotal.inc(bytes.toDouble)
                Some(url -> filename)
              case None =>
                imagesFailed.inc()
                None
            }
          } finally {
            imagesInFlight.dec()
          }
        }
      }
      .collect { case Some(pair) => pair }
      .runWith(Sink.seq)

    val results = Await.result(resultFuture, 5.minutes)
    logger.info(s"Downloaded ${results.size}/${imageUrls.size} images for link $linkId")
    results.toMap
  }

  /** Download a single image, returning (filename, byteCount) */
  private def downloadImage(imageUrl: String, assetsDir: Path, linkId: Long): Option[(String, Long)] = {
    Try {
      val url  = new URL(imageUrl)
      val conn = url.openConnection().asInstanceOf[HttpURLConnection]
      conn.setRequestProperty("User-Agent", "Markko/1.0")
      conn.setConnectTimeout(5000)
      conn.setReadTimeout(10000)

      try {
        // Pre-check Content-Length
        val contentLength = conn.getContentLengthLong
        if (contentLength > MaxImageBytes) {
          logger.info(s"Skipping image $imageUrl — too large (${contentLength / 1024 / 1024}MB)")
          return None
        }

        val contentType = Option(conn.getContentType).getOrElse("image/png")
        val ext = contentType match {
          case t if t.contains("jpeg") || t.contains("jpg") => "jpg"
          case t if t.contains("png")                        => "png"
          case t if t.contains("gif")                        => "gif"
          case t if t.contains("webp")                       => "webp"
          case t if t.contains("svg")                        => "svg"
          case _                                             => "png"
        }

        // Generate unique filename
        val hash     = imageUrl.hashCode.abs.toString
        val filename = s"${linkId}_$hash.$ext"
        val target   = assetsDir.resolve(filename)

        if (!Files.exists(target)) {
          val is = conn.getInputStream
          try {
            Files.copy(is, target)
          } finally {
            is.close()
          }
        }

        val fileSize = Files.size(target)

        // Post-check: delete if over limit
        if (fileSize > MaxImageBytes) {
          Files.delete(target)
          logger.info(s"Deleted downloaded image $imageUrl — exceeded 10MB limit after download")
          None
        } else {
          Some((filename, fileSize))
        }
      } finally {
        conn.disconnect()
      }
    }.recover {
      case ex: Exception =>
        logger.warn(s"Failed to download image $imageUrl: ${ex.getMessage}")
        None
    }.get
  }
}
