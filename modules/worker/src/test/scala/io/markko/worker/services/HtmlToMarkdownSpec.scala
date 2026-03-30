package io.markko.worker.services

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HtmlToMarkdownSpec extends AnyFlatSpec with Matchers {

  val converter = new HtmlToMarkdown()

  "HtmlToMarkdown" should "convert simple HTML to Markdown" in {
    val html =
      """<html><body>
        |<article>
        |  <h1>Hello World</h1>
        |  <p>This is a <strong>test</strong> paragraph.</p>
        |</article>
        |</body></html>""".stripMargin

    val result = converter.convert(html, "https://example.com")
    result.content should include("Hello World")
    result.content should include("**test**")
  }

  it should "extract meta description" in {
    val html =
      """<html><head>
        |<meta name="description" content="A great article about Scala">
        |</head><body><article><p>Content here</p></article></body></html>""".stripMargin

    val result = converter.convert(html, "https://example.com")
    result.description shouldBe Some("A great article about Scala")
  }

  it should "extract og:image" in {
    val html =
      """<html><head>
        |<meta property="og:image" content="https://example.com/image.jpg">
        |</head><body><article><p>Content</p></article></body></html>""".stripMargin

    val result = converter.convert(html, "https://example.com")
    result.ogImage shouldBe Some("https://example.com/image.jpg")
  }

  it should "strip script and style tags" in {
    val html =
      """<html><body>
        |<article>
        |  <p>Real content</p>
        |  <script>alert('xss')</script>
        |  <style>.foo { color: red; }</style>
        |</article>
        |</body></html>""".stripMargin

    val result = converter.convert(html, "https://example.com")
    result.content should not include "alert"
    result.content should not include "color: red"
    result.content should include("Real content")
  }

  it should "strip navigation and footer elements" in {
    val html =
      """<html><body>
        |<nav><a href="/">Home</a></nav>
        |<article><p>Article content</p></article>
        |<footer><p>Copyright 2024</p></footer>
        |</body></html>""".stripMargin

    val result = converter.convert(html, "https://example.com")
    result.content should include("Article content")
    result.content should not include "Copyright"
  }

  it should "convert HTML tables to Markdown tables" in {
    val html =
      """<html><body><article>
        |<table>
        |  <thead><tr><th>Name</th><th>Age</th></tr></thead>
        |  <tbody><tr><td>Alice</td><td>30</td></tr></tbody>
        |</table>
        |</article></body></html>""".stripMargin

    val result = converter.convert(html, "https://example.com")
    result.content should include("Name")
    result.content should include("Alice")
    result.content should include("|")
  }

  it should "return empty markdown for empty HTML" in {
    val result = converter.convert("<html><body></body></html>", "https://example.com")
    result.content.trim shouldBe empty
  }

  it should "handle malformed HTML gracefully" in {
    val html = "<div><p>Unclosed paragraph<div>Nested badly</p></div>"
    noException should be thrownBy {
      converter.convert(html, "https://example.com")
    }
  }
}
