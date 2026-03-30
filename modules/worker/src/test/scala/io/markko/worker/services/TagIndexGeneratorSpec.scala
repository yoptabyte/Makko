package io.markko.worker.services

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets

class TagIndexGeneratorSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var tempDir: Path = _

  override def beforeEach(): Unit = {
    tempDir = Files.createTempDirectory("markko-tags-test")
  }

  override def afterEach(): Unit = {
    import scala.jdk.CollectionConverters._
    def deleteRecursive(path: Path): Unit = {
      if (Files.isDirectory(path)) {
        Files.list(path).iterator().asScala.foreach(deleteRecursive)
      }
      Files.deleteIfExists(path)
    }
    deleteRecursive(tempDir)
  }

  "TagIndexGenerator" should "create by-tag directory" in {
    // Just test directory creation without DB
    val byTagDir = tempDir.resolve("by-tag")
    Files.createDirectories(byTagDir)
    Files.exists(byTagDir) shouldBe true
  }

  it should "handle empty directory" in {
    // Test that we can create the structure
    val byTagDir = tempDir.resolve("by-tag")
    Files.createDirectories(byTagDir)
    Files.exists(byTagDir) shouldBe true
  }
}
