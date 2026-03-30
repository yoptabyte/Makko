package controllers

import actions.AuthAction
import javax.inject._
import play.api.Configuration
import play.api.libs.json.Json
import play.api.mvc._

import java.nio.file.{Files, Path, Paths}
import scala.sys.process._
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
  private val systemPickerEnabled: Boolean =
    config.getOptional[Boolean]("markko.vault.enable-system-picker").getOrElse(false)

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

  def pickDirectory(): Action[AnyContent] = authAction.async { _ =>
    Future {
      if (!systemPickerEnabled) {
        Forbidden(Json.obj("error" -> "System directory picker is disabled by configuration"))
      } else {
        val chooser = findChooser()

        chooser match {
          case None =>
            NotImplemented(Json.obj("error" -> "No system directory picker is available"))

          case Some(command) =>
            val output = new StringBuilder
            val logger = new StringBuilder
            val exitCode = Process(command).!(ProcessLogger(output.append(_), logger.append(_)))

            if (exitCode == 0) {
              val selected = output.toString.trim
              if (selected.nonEmpty) {
                Ok(Json.obj("path" -> Paths.get(selected).toAbsolutePath.normalize().toString))
              } else {
                NoContent
              }
            } else if (exitCode == 1) {
              NoContent
            } else {
              InternalServerError(
                Json.obj(
                  "error" -> "Failed to open system directory picker",
                  "details" -> logger.toString.trim
                )
              )
            }
        }
      }
    }
  }

  private def findChooser(): Option[Seq[String]] =
    Seq(
      Seq("/usr/sbin/zenity", "--file-selection", "--directory", "--title=Select Markko export directory"),
      Seq("zenity", "--file-selection", "--directory", "--title=Select Markko export directory")
    ).find { command =>
      command.headOption.exists { binary =>
        if (binary.startsWith("/")) {
          Files.isExecutable(Paths.get(binary))
        } else {
          scala.util.Try(Process(Seq("sh", "-lc", s"command -v $binary")).!!.trim.nonEmpty).getOrElse(false)
        }
      }
    }
}
