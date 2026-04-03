package controllers

import actions.{AuthAction, AuthRequest}
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

  private def getUserIdOrError(request: AuthRequest[_]): Either[Result, Long] =
    request.identity.id.toRight(InternalServerError(Json.obj("error" -> "User ID not available")))

  def download(id: Long): Action[AnyContent] = authAction.async { request =>
    getUserIdOrError(request) match {
      case Left(err) => Future.successful(err)
      case Right(userId) =>
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
  }

  def status(id: Long): Action[AnyContent] = authAction.async { request =>
    getUserIdOrError(request) match {
      case Left(err) => Future.successful(err)
      case Right(userId) =>
        linkRepo.findById(id, Some(userId)).flatMap {
          case None =>
            Future.successful(NotFound(Json.obj("error" -> "Link not found")))
          case Some(_) =>
            exportJobRepo.findByLinkId(id).map { jobs =>
              Ok(Json.toJson(jobs))
            }
        }
    }
  }

  def reexport(id: Long): Action[AnyContent] = authAction.async { request =>
    getUserIdOrError(request) match {
      case Left(err) => Future.successful(err)
      case Right(userId) =>
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
  }

  def deleteExport(id: Long): Action[AnyContent] = authAction.async { request =>
    getUserIdOrError(request) match {
      case Left(err) => Future.successful(err)
      case Right(userId) =>
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
