package controllers

import actions.{AuthAction, AuthRequest}
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

  private def getUserIdOrError(request: AuthRequest[_]): Either[Result, Long] =
    request.identity.id.toRight(InternalServerError(Json.obj("error" -> "User ID not available")))

  def list(): Action[AnyContent] = authAction.async { request =>
    getUserIdOrError(request) match {
      case Left(err) => Future.successful(err)
      case Right(userId) =>
        collectionRepo.findAllByUser(userId).map { collections =>
          Ok(Json.toJson(collections))
        }
    }
  }

  def create(): Action[JsValue] = authAction.async(parse.json) { request =>
    getUserIdOrError(request) match {
      case Left(err) => Future.successful(err)
      case Right(userId) =>
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
  }

  def update(id: Long): Action[JsValue] = authAction.async(parse.json) { request =>
    getUserIdOrError(request) match {
      case Left(err) => Future.successful(err)
      case Right(userId) =>
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
  }

  def delete(id: Long): Action[AnyContent] = authAction.async { request =>
    getUserIdOrError(request) match {
      case Left(err) => Future.successful(err)
      case Right(userId) =>
        collectionRepo.delete(id, userId).map {
          case true  => Ok(Json.obj("message" -> "Collection deleted", "id" -> id))
          case false => NotFound(Json.obj("error" -> "Collection not found"))
        }
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
