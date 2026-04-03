package controllers

import actions.{AuthAction, AuthRequest}
import javax.inject._
import play.api.mvc._
import play.api.libs.json._
import services.ElasticsearchService

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SearchController @Inject()(
  cc:         ControllerComponents,
  esService:  ElasticsearchService,
  authAction: AuthAction
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  def search(q: String): Action[AnyContent] = authAction.async { request =>
    request.identity.id match {
      case Some(userId) =>
        esService.search(q, userId).map { results =>
          Ok(Json.toJson(results))
        }.recover {
          case ex: Exception =>
            ServiceUnavailable(Json.obj(
              "error"   -> "Search temporarily unavailable",
              "details" -> ex.getMessage
            ))
        }
      case None =>
        Future.successful(InternalServerError(Json.obj("error" -> "User ID not available")))
    }
  }

  def searchAdvanced(
    q:          Option[String],
    tag:        Option[String],
    collection: Option[String],
    limit:      Option[Int],
    offset:     Option[Int]
  ): Action[AnyContent] = authAction.async { request =>
    request.identity.id match {
      case Some(userId) =>
        val tags = tag.toList
        esService.searchAdvanced(q, tags, collection, userId, limit.getOrElse(20), offset.getOrElse(0)).map { result =>
          Ok(Json.obj(
            "total"  -> result.total,
            "offset" -> result.offset,
            "limit"  -> result.limit,
            "hits"   -> result.hits
          ))
        }.recover {
          case ex: Exception =>
            ServiceUnavailable(Json.obj("error" -> ex.getMessage))
        }
      case None =>
        Future.successful(InternalServerError(Json.obj("error" -> "User ID not available")))
    }
  }

  def searchByTag(tag: String): Action[AnyContent] = authAction.async { request =>
    request.identity.id match {
      case Some(userId) =>
        esService.searchByTag(tag, userId).map { results =>
          Ok(Json.toJson(results))
        }.recover {
          case ex: Exception =>
            ServiceUnavailable(Json.obj("error" -> ex.getMessage))
        }
      case None =>
        Future.successful(InternalServerError(Json.obj("error" -> "User ID not available")))
    }
  }

  def suggest(q: String): Action[AnyContent] = authAction.async { request =>
    request.identity.id match {
      case Some(userId) =>
        esService.suggest(q, userId).map { results =>
          Ok(Json.toJson(results))
        }.recover {
          case ex: Exception =>
            ServiceUnavailable(Json.obj("error" -> ex.getMessage))
        }
      case None =>
        Future.successful(InternalServerError(Json.obj("error" -> "User ID not available")))
    }
  }
}
