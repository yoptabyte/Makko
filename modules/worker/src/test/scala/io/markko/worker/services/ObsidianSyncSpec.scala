package io.markko.worker.services

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets

class ObsidianSyncSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var tempDir: Path = _
  private var sync: ObsidianSync = _

  override def beforeEach(): Unit = {
    tempDir = Files.createTempDirectory("markko-vault-test")
    sync = new ObsidianSync(tempDir.toString)
  }

  override def afterEach(): Unit = {
    // Clean up temp dir
    import scala.jdk.CollectionConverters._
    def deleteRecursive(path: Path): Unit = {
      if (Files.isDirectory(path)) {
        Files.list(path).iterator().asScala.foreach(deleteRecursive)
      }
      Files.deleteIfExists(path)
    }
    deleteRecursive(tempDir)
  }

  "ObsidianSync" should "write a note with YAML frontmatter" in {
    val notePath = sync.writeNote(
      title        = "Test Article",
      url          = "https://example.com/test",
      tags         = List("scala", "test"),
      collection   = "dev",
      exportDirectory = None,
      exportFileName  = None,
      contentMd    = "# Hello\n\nThis is content.",
      readingTime  = 5,
      relatedLinks = List("related-article"),
      images       = Map.empty,
      linkId       = 1L
    )

    val content = Files.readString(notePath, StandardCharsets.UTF_8)
    content should include("title: \"Test Article\"")
    content should include("url: \"https://example.com/test\"")
    content should include("tags:")
    content should include("scala")
    content should include("# Hello")
    content should include("[[related-article]]")
  }

  it should "create collection subdirectory" in {
    sync.writeNote("Test", "https://example.com", List("tag"), "my-collection", None, None, "content", 5, Nil, Map.empty, 1L)

    Files.exists(tempDir.resolve("collections").resolve("my-collection")) shouldBe true
  }

  it should "use 'unsorted' collection when empty" in {
    sync.writeNote("Test", "https://example.com", List(), "", None, None, "content", 5, Nil, Map.empty, 1L)

    Files.exists(tempDir.resolve("collections").resolve("unsorted")) shouldBe true
  }

  it should "track notes in .markko-meta.json" in {
    sync.writeNote("Test", "https://example.com", List("tag"), "dev", None, None, "content", 5, Nil, Map.empty, 1L)

    val metaPath = tempDir.resolve(".markko-meta.json")
    Files.exists(metaPath) shouldBe true

    val metaContent = Files.readString(metaPath, StandardCharsets.UTF_8)
    metaContent should include("\"1\"")
    metaContent should include("test")
  }

  it should "delete a note by linkId" in {
    val notePath = sync.writeNote("Delete Me", "https://example.com",
      List(), "dev", None, None, "content", 5, Nil, Map.empty, 1L)

    Files.exists(notePath) shouldBe true

    sync.deleteByLinkId(1L)

    Files.exists(notePath) shouldBe false
  }

  it should "rebuild _index.md" in {
    sync.writeNote("First", "https://a.com", List(), "dev", None, None, "c1", 5, Nil, Map.empty, 1L)
    sync.writeNote("Second", "https://b.com", List(), "dev", None, None, "c2", 5, Nil, Map.empty, 2L)

    val indexPath = tempDir.resolve("_index.md")
    Files.exists(indexPath) shouldBe true

    val content = Files.readString(indexPath, StandardCharsets.UTF_8)
    content should include("first")
    content should include("second")
    content should include("📚")
  }

  it should "replace image URLs with local paths" in {
    val imageMap = Map("https://example.com/img.png" -> "1_img.png")
    val notePath = sync.writeNote("Images", "https://example.com",
      List(), "dev", None, None, "Check ![img](https://example.com/img.png)", 5, Nil, imageMap, 1L)

    val content = Files.readString(notePath, StandardCharsets.UTF_8)
    content should include("assets/1_img.png")
    content should not include "https://example.com/img.png"
  }

  it should "slugify titles correctly" in {
    sync.writeNote("Hello World! (2024)", "https://example.com",
      List(), "dev", None, None, "content", 5, Nil, Map.empty, 1L)

    val files = Files.list(tempDir.resolve("collections").resolve("dev")).iterator()
    val names = new scala.collection.mutable.ListBuffer[String]()
    while (files.hasNext) names += files.next().getFileName.toString
    names should contain("hello-world-2024.md")
  }
}
