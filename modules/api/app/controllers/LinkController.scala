package controllers

import actions.{AuthAction, AuthRequest}
import javax.inject._
import play.api.mvc._
import play.api.libs.json._
import repositories.LinkRepository
import services.{RedisService, ElasticsearchService}

import scala.concurrent.{ExecutionContext, Future}
import java.security.MessageDigest

@Singleton
class LinkController @Inject()(
  cc:        ControllerComponents,
  linkRepo:  LinkRepository,
  redisService: RedisService,
  esService: ElasticsearchService,
  authAction: AuthAction
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  private def getUserIdOrError(request: AuthRequest[_]): Either[Result, Long] =
    request.identity.id.toRight(InternalServerError(Json.obj("error" -> "User ID not available")))

  def create(): Action[JsValue] = authAction.async(parse.json) { request =>
    request.body.validate[JsObject].fold(
      errors => Future.successful(BadRequest(Json.obj("error" -> "Invalid JSON"))),
      json => {
        val url             = (json \ "url").as[String]
        val tags            = (json \ "tags").asOpt[List[String]].getOrElse(Nil)
        val collectionId    = (json \ "collectionId").asOpt[Long]
        val exportDirectory = normalizeOptionalString((json \ "exportDirectory").asOpt[String])
        val exportFileName  = normalizeOptionalString((json \ "exportFileName").asOpt[String])

        val urlHash = sha256(url)

        getUserIdOrError(request) match {
          case Left(err) => Future.successful(err)
          case Right(uid) =>
            redisService.checkDedup(urlHash, uid).flatMap {
              case true =>
                Future.successful(Conflict(Json.obj("error" -> "Link already exists")))
              case false =>
                for {
                  _      <- redisService.setDedup(urlHash, uid)
                  linkId <- linkRepo.insert(url, urlHash, collectionId, uid, exportDirectory, exportFileName)
                  _      <- linkRepo.attachTags(linkId, tags)
                  _      <- redisService.enqueueParseJob(linkId)
                  link   <- linkRepo.findById(linkId)
                } yield {
                  link match {
                    case Some(l) => Created(l)
                    case None    => InternalServerError(Json.obj("error" -> "Failed to retrieve created link"))
                  }
                }
            }
        }
      }
    )
  }

  def list(limit: Option[Int], offset: Option[Int]): Action[AnyContent] = authAction.async { request =>
    getUserIdOrError(request) match {
      case Left(err) => Future.successful(err)
      case Right(userId) =>
        val lim = limit.getOrElse(20).min(100)
        val off = offset.getOrElse(0)
        for {
          links <- linkRepo.findAllByUser(userId, lim, off)
          total <- linkRepo.countByUser(userId)
        } yield Ok(Json.obj(
          "total"  -> total,
          "limit"  -> lim,
          "offset" -> off,
          "links"  -> links
        ))
    }
  }

  def get(id: Long): Action[AnyContent] = authAction.async { request =>
    getUserIdOrError(request) match {
      case Left(err) => Future.successful(err)
      case Right(userId) =>
        linkRepo.findById(id, Some(userId)).map {
          case Some(linkJson) => Ok(linkJson)
          case None           => NotFound(Json.obj("error" -> "Link not found"))
        }
    }
  }

  def update(id: Long): Action[JsValue] = authAction.async(parse.json) { request =>
    getUserIdOrError(request) match {
      case Left(err) => Future.successful(err)
      case Right(userId) =>
        val tags            = (request.body \ "tags").asOpt[List[String]].getOrElse(Nil)
        val collectionId    = (request.body \ "collectionId").asOpt[Long]
        val exportDirectory = normalizeOptionalString((request.body \ "exportDirectory").asOpt[String])
        val exportFileName  = normalizeOptionalString((request.body \ "exportFileName").asOpt[String])

        linkRepo.update(id, userId, collectionId, tags, exportDirectory, exportFileName).flatMap {
          case true =>
            linkRepo.findById(id, Some(userId)).map {
              case Some(linkJson) => Ok(linkJson)
              case None           => InternalServerError(Json.obj("error" -> "Update succeeded but link not found"))
            }
          case false =>
            Future.successful(NotFound(Json.obj("error" -> "Link not found")))
        }
    }
  }

  def delete(id: Long): Action[AnyContent] = authAction.async { request =>
    getUserIdOrError(request) match {
      case Left(err) => Future.successful(err)
      case Right(userId) =>
        linkRepo.delete(id, userId).flatMap {
          case true =>
            esService.deleteLink(id).map { _ =>
              Ok(Json.obj("message" -> "Link deleted", "id" -> id))
            }
          case false =>
            Future.successful(NotFound(Json.obj("error" -> "Link not found")))
        }
    }
  }

  private def sha256(s: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(s.getBytes("UTF-8")).map("%02x".format(_)).mkString
  }

  private def normalizeOptionalString(value: Option[String]): Option[String] =
    value.map(_.trim).filter(_.nonEmpty)
}
