package controllers

import javax.inject._
import play.api.mvc._
import play.api.libs.json._

@Singleton
class HealthController @Inject()(
  cc: ControllerComponents
) extends AbstractController(cc) {

  /** GET /health — Health check for Docker/k8s */
  def health(): Action[AnyContent] = Action {
    Ok(Json.obj(
      "status"  -> "ok",
      "service" -> "markko-api"
    ))
  }
}
