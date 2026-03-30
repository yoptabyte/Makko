package io.markko.worker.services

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import com.vladsch.flexmark.util.data.MutableDataSet
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.autolink.AutolinkExtension
import org.jsoup.Jsoup
import com.typesafe.scalalogging.LazyLogging

import java.util.{Arrays => JArrays}

/**
 * Convert HTML pages to clean Markdown using flexmark-java.
 * Extracts metadata (title, description, og:image) and supports
 * GFM tables, strikethrough, and autolink extensions.
 */
class HtmlToMarkdown extends LazyLogging {

  private val options = {
    val opts = new MutableDataSet()
    opts.set(
      FlexmarkHtmlConverter.OUTPUT_ATTRIBUTES_ID, java.lang.Boolean.FALSE
    )
    opts
  }

  private val extensions = JArrays.asList(
    TablesExtension.create(),
    StrikethroughExtension.create(),
    AutolinkExtension.create()
  )

  private val converter = FlexmarkHtmlConverter.builder(options).build()

  /** Result of parsing an HTML page */
  case class ParsedPage(
    title:       String,
    content:     String,
    description: Option[String],
    ogImage:     Option[String]
  )

  /**
   * Convert HTML to Markdown with full metadata extraction.
   * Returns a ParsedPage with title, content, description, and og:image.
   */
  def convert(html: String, sourceUrl: String): ParsedPage = {
    val doc = Jsoup.parse(html)

    // Extract title (priority: og:title > <title> > h1 > URL)
    val title = extractMeta(doc, "og:title")
      .orElse(Option(doc.title()).filter(_.nonEmpty))
      .orElse(Option(doc.selectFirst("h1")).map(_.text()))
      .getOrElse(sourceUrl)

    // Extract meta description
    val description = extractMeta(doc, "description")
      .orElse(extractMeta(doc, "og:description"))

    // Extract og:image
    val ogImage = extractMeta(doc, "og:image")

    // Remove scripts, styles, nav, footer, ads, cookies banners
    doc.select(
      "script, style, nav, footer, header, noscript, iframe, " +
      ".ads, .sidebar, .comments, #comments, .cookie-banner, " +
      ".popup, .modal, .newsletter, [role=navigation], [role=banner]"
    ).remove()

    // Try to find the main content area
    val contentElement = Option(doc.selectFirst("article"))
      .orElse(Option(doc.selectFirst("main")))
      .orElse(Option(doc.selectFirst("[role=main]")))
      .orElse(Option(doc.selectFirst(".post-content")))
      .orElse(Option(doc.selectFirst(".entry-content")))
      .orElse(Option(doc.selectFirst(".article-content")))
      .orElse(Option(doc.selectFirst(".markdown-body"))) // GitHub
      .orElse(Option(doc.selectFirst(".content")))
      .getOrElse(doc.body())

    // Preserve code blocks: mark <pre><code> blocks before conversion
    contentElement.select("pre code").forEach { codeBlock =>
      val lang = Option(codeBlock.className())
        .filter(_.nonEmpty)
        .map(_.replace("language-", "").replace("lang-", ""))
        .getOrElse("")
      codeBlock.attr("data-lang", lang)
    }

    val contentHtml = contentElement.html()

    // Convert to Markdown
    val markdown = converter.convert(contentHtml)

    // Clean up
    val cleaned = markdown
      .replaceAll("\n{3,}", "\n\n")       // excessive newlines
      .replaceAll("(?m)^\\s+$", "")       // whitespace-only lines
      .replaceAll("\\[\\s*\\]\\(\\)", "")  // empty links
      .trim

    ParsedPage(
      title       = title,
      content     = cleaned,
      description = description,
      ogImage     = ogImage
    )
  }

  /** Extract content from <meta> tags (name or property based) */
  private def extractMeta(doc: org.jsoup.nodes.Document, key: String): Option[String] = {
    val byName = Option(doc.selectFirst(s"""meta[name="$key"]"""))
      .map(_.attr("content"))
      .filter(_.nonEmpty)

    val byProperty = Option(doc.selectFirst(s"""meta[property="$key"]"""))
      .map(_.attr("content"))
      .filter(_.nonEmpty)

    byName.orElse(byProperty)
  }
}
