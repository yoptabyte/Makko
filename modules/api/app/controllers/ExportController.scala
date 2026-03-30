package controllers

import actions.AuthAction
import javax.inject._
import play.api.mvc._
import play.api.libs.json._
import repositories.{LinkRepository, ExportJobRepository}
import services.RedisService

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ExportController @Inject()(
  cc:             ControllerComponents,
  linkRepo:       LinkRepository,
  exportJobRepo:  ExportJobRepository,
  redisService:   RedisService,
  authAction: AuthAction
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  /** GET /export/:id — Download link as Obsidian-compatible .md file */
  def download(id: Long): Action[AnyContent] = authAction.async { request =>
    val userId = request.identity.id.get
    linkRepo.findById(id, Some(userId)).map {
      case None =>
        NotFound(Json.obj("error" -> "Link not found"))
      case Some(linkJson) =>
        val title     = (linkJson \ "title").asOpt[String].getOrElse("untitled")
        val url       = (linkJson \ "url").as[String]
        val contentMd = (linkJson \ "contentMd").asOpt[String].getOrElse("")
        val tags      = (linkJson \ "tags").asOpt[List[String]].getOrElse(Nil)
        val savedAt   = (linkJson \ "savedAt").asOpt[String].getOrElse("")
        val parsedAt  = (linkJson \ "parsedAt").asOpt[String].getOrElse("")
        val readTime  = (linkJson \ "readingTimeMin").asOpt[Int].getOrElse(0)

        val markdown = buildObsidianMarkdown(title, url, tags, savedAt, parsedAt, readTime, contentMd)
        val filename = slugify(title) + ".md"

        Ok(markdown)
          .as("text/markdown; charset=utf-8")
          .withHeaders(CONTENT_DISPOSITION -> s"""attachment; filename="$filename"""")
    }
  }

  /** GET /export/:id/status — Get export job status for a link */
  def status(id: Long): Action[AnyContent] = authAction.async { request =>
    val userId = request.identity.id.get
    linkRepo.findById(id, Some(userId)).flatMap {
      case None =>
        Future.successful(NotFound(Json.obj("error" -> "Link not found")))
      case Some(_) =>
        exportJobRepo.findByLinkId(id).map { jobs =>
          Ok(Json.toJson(jobs))
        }
    }
  }

  /** POST /export/:id/reexport — Trigger re-export to Obsidian vault */
  def reexport(id: Long): Action[AnyContent] = authAction.async { request =>
    val userId = request.identity.id.get
    linkRepo.findById(id, Some(userId)).flatMap {
      case None =>
        Future.successful(NotFound(Json.obj("error" -> "Link not found")))
      case Some(linkJson) =>
        val status = (linkJson \ "status").as[String]
        if (status != "parsed") {
          Future.successful(BadRequest(Json.obj("error" -> s"Link not parsed yet (status: $status)")))
        } else {
          for {
            jobId <- exportJobRepo.insert(id)
            _     <- redisService.enqueueExportJob(id)
          } yield {
            Accepted(Json.obj(
              "message"    -> "Re-export queued",
              "exportJobId" -> jobId,
              "linkId"     -> id
            ))
          }
        }
    }
  }

  /** DELETE /export/:id — Remove link from Obsidian vault */
  def deleteExport(id: Long): Action[AnyContent] = authAction.async { request =>
    val userId = request.identity.id.get
    linkRepo.findById(id, Some(userId)).flatMap {
      case None =>
        Future.successful(NotFound(Json.obj("error" -> "Link not found")))
      case Some(_) =>
        exportJobRepo.findLatestExportedPath(id).flatMap {
          case None =>
            Future.successful(NotFound(Json.obj("error" -> "No successful export found for this link")))
          case Some(exportJob) =>
            redisService.enqueueDeleteJob(id).map { _ =>
              Ok(Json.obj(
                "message"   -> "Delete queued",
                "linkId"    -> id,
                "vaultPath" -> (exportJob \ "vaultPath").asOpt[String]
              ))
            }
        }
    }
  }

  private def buildObsidianMarkdown(
    title: String, url: String, tags: List[String],
    savedAt: String, parsedAt: String, readTime: Int, contentMd: String
  ): String = {
    val tagList = tags.map(t => s""""$t"""").mkString("[", ", ", "]")
    s"""---
       |title: "$title"
       |url: "$url"
       |tags: $tagList
       |saved_at: $savedAt
       |parsed_at: $parsedAt
       |reading_time: ${readTime}min
       |---
       |
       |# $title
       |
       |$contentMd
       |""".stripMargin
  }

  private def slugify(s: String): String =
    s.toLowerCase
      .replaceAll("[^a-z0-9\\s-]", "")
      .replaceAll("\\s+", "-")
      .take(80)
}
