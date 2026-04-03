package controllers

import actions.AuthAction
import javax.inject._
import play.api.Configuration
import play.api.libs.json.Json
import play.api.mvc._

import java.nio.file.{Files, Path, Paths}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

@Singleton
class VaultController @Inject()(
  cc: ControllerComponents,
  config: Configuration,
  authAction: AuthAction
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  private val collectionsRoot: Path =
    Paths.get(config.get[String]("markko.vault.base-path")).toAbsolutePath.normalize().resolve("collections")

  def directories(): Action[AnyContent] = authAction.async { _ =>
    Future {
      Files.createDirectories(collectionsRoot)

      val directories =
        Files.walk(collectionsRoot)
          .iterator()
          .asScala
          .filter(path => Files.isDirectory(path))
          .map(path => collectionsRoot.relativize(path).toString.replace('\\', '/'))
          .filter(_.nonEmpty)
          .toList
          .sorted

      Ok(
        Json.obj(
          "root"        -> "collections",
          "directories" -> directories
        )
      )
    }
  }
}
