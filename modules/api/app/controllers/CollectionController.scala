package controllers

import actions.AuthAction
import javax.inject._
import play.api.mvc._
import play.api.libs.json._
import repositories.CollectionRepository

import java.sql.SQLIntegrityConstraintViolationException
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class CollectionController @Inject()(
  cc:             ControllerComponents,
  collectionRepo: CollectionRepository,
  authAction: AuthAction
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  /** GET /collections — List user's collections */
  def list(): Action[AnyContent] = authAction.async { request =>
    val userId = request.identity.id.get
    collectionRepo.findAllByUser(userId).map { collections =>
      Ok(Json.toJson(collections))
    }
  }

  /** POST /collections — Create a new collection */
  def create(): Action[JsValue] = authAction.async(parse.json) { request =>
    val userId = request.identity.id.get
    (request.body \ "name").asOpt[String] match {
      case None =>
        Future.successful(BadRequest(Json.obj("error" -> "Missing 'name' field")))
      case Some(rawName) =>
        val name = rawName.trim
        val slug = slugify(name)

        if (name.isEmpty) {
          Future.successful(BadRequest(Json.obj("error" -> "Collection name cannot be empty")))
        } else if (slug.isEmpty) {
          Future.successful(BadRequest(Json.obj("error" -> "Collection name must contain letters or numbers")))
        } else {
          collectionRepo.insert(name, slug, userId)
            .map { id =>
              Created(Json.obj("id" -> id, "name" -> name, "slug" -> slug))
            }
            .recover {
              case _: SQLIntegrityConstraintViolationException =>
                Conflict(Json.obj("error" -> "Collection with this name already exists"))
            }
        }
    }
  }

  /** PUT /collections/:id — Update a collection */
  def update(id: Long): Action[JsValue] = authAction.async(parse.json) { request =>
    val userId = request.identity.id.get
    (request.body \ "name").asOpt[String] match {
      case None =>
        Future.successful(BadRequest(Json.obj("error" -> "Missing 'name' field")))
      case Some(rawName) =>
        val name = rawName.trim
        val slug = slugify(name)

        if (name.isEmpty) {
          Future.successful(BadRequest(Json.obj("error" -> "Collection name cannot be empty")))
        } else if (slug.isEmpty) {
          Future.successful(BadRequest(Json.obj("error" -> "Collection name must contain letters or numbers")))
        } else {
          collectionRepo.update(id, userId, name, slug).map {
            case true  => Ok(Json.obj("id" -> id, "name" -> name, "slug" -> slug))
            case false => NotFound(Json.obj("error" -> "Collection not found"))
          }.recover {
            case _: SQLIntegrityConstraintViolationException =>
              Conflict(Json.obj("error" -> "Collection with this name already exists"))
          }
        }
    }
  }

  /** DELETE /collections/:id — Delete a collection */
  def delete(id: Long): Action[AnyContent] = authAction.async { request =>
    val userId = request.identity.id.get
    collectionRepo.delete(id, userId).map {
      case true  => Ok(Json.obj("message" -> "Collection deleted", "id" -> id))
      case false => NotFound(Json.obj("error" -> "Collection not found"))
    }
  }

  private def slugify(value: String): String =
    value.toLowerCase
      .replaceAll("[^\\p{L}\\p{Nd}\\s-]", " ")
      .replaceAll("\\s+", "-")
      .replaceAll("-{2,}", "-")
      .stripPrefix("-")
      .stripSuffix("-")
      .take(100)
}
