package controllers

import actions.AuthAction
import javax.inject._
import play.api.mvc._
import play.api.libs.json._
import services.ElasticsearchService

import scala.concurrent.ExecutionContext

@Singleton
class SearchController @Inject()(
  cc:         ControllerComponents,
  esService:  ElasticsearchService,
  authAction: AuthAction
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  /** GET /links/search?q=scala — Full-text search via Elasticsearch */
  def search(q: String): Action[AnyContent] = authAction.async { request =>
    val userId = request.identity.id.get
    esService.search(q, userId).map { results =>
      Ok(Json.toJson(results))
    }.recover {
      case ex: Exception =>
        ServiceUnavailable(Json.obj(
          "error"   -> "Search temporarily unavailable",
          "details" -> ex.getMessage
        ))
    }
  }

  /** GET /links/search/advanced?q=&tag=&collection=&limit=&offset= */
  def searchAdvanced(
    q:          Option[String],
    tag:        Option[String],
    collection: Option[String],
    limit:      Option[Int],
    offset:     Option[Int]
  ): Action[AnyContent] = authAction.async { request =>
    val userId = request.identity.id.get
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
  }

  /** GET /links/search/tag/:tag — Search by tag */
  def searchByTag(tag: String): Action[AnyContent] = authAction.async { request =>
    val userId = request.identity.id.get
    esService.searchByTag(tag, userId).map { results =>
      Ok(Json.toJson(results))
    }.recover {
      case ex: Exception =>
        ServiceUnavailable(Json.obj("error" -> ex.getMessage))
    }
  }

  /** GET /links/suggest?q=pek — Autocomplete suggestions */
  def suggest(q: String): Action[AnyContent] = authAction.async { request =>
    val userId = request.identity.id.get
    esService.suggest(q, userId).map { results =>
      Ok(Json.toJson(results))
    }.recover {
      case ex: Exception =>
        ServiceUnavailable(Json.obj("error" -> ex.getMessage))
    }
  }
}
