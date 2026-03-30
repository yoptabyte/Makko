package filters

import javax.inject.{Inject, Singleton}
import org.apache.pekko.stream.Materializer
import play.api.Configuration
import play.api.mvc._
import play.api.libs.json.Json
import scala.concurrent.{ExecutionContext, Future}
import services.{RateLimitConfig, RateLimitService}
import cats.effect.unsafe.implicits.global

@Singleton
class RateLimitFilter @Inject()(
  rateLimitService: RateLimitService,
  config: Configuration
)(implicit val mat: Materializer, ec: ExecutionContext) extends Filter {

  private val authConfig = loadConfig("markko.rate-limit.auth")
  private val generalConfig = loadConfig("markko.rate-limit.general")
  private val searchConfig = loadConfig("markko.rate-limit.search")

  def apply(next: RequestHeader => Future[Result])(request: RequestHeader): Future[Result] = {
    if (isExcludedPath(request.path)) {
      next(request)
    } else {
      val key = generateKey(request)
      val rateLimit = getConfig(request)

      rateLimitService.isAllowed(key, rateLimit).unsafeToFuture().flatMap { allowed =>
        if (allowed) {
          next(request)
        } else {
          rateLimitService.getResetTime(key, rateLimit).unsafeToFuture().map { resetTime =>
            Results.TooManyRequests(Json.obj(
              "error" -> "Rate limit exceeded",
              "resetTime" -> resetTime,
              "message" -> s"Too many requests. Try again in ${rateLimit.windowSizeSeconds} seconds."
            ))
          }
        }
      }
    }
  }

  private def loadConfig(path: String): RateLimitConfig =
    RateLimitConfig(
      windowSizeSeconds = config.get[Int](s"$path.window-size-seconds"),
      maxRequests = config.get[Int](s"$path.max-requests")
    )

  private def isExcludedPath(path: String): Boolean =
    path == "/health" || path == "/metrics"

  private def generateKey(request: RequestHeader): String = {
    val clientIp = request.headers
      .get("X-Forwarded-For")
      .flatMap(_.split(",").headOption)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(request.remoteAddress)

    val prefix =
      if (request.path.startsWith("/auth/")) "auth"
      else if (request.path.startsWith("/links/search")) "search"
      else "general"

    s"rate-limit:$prefix:$clientIp"
  }

  private def getConfig(request: RequestHeader): RateLimitConfig = {
    val key = generateKey(request)
    if (key.startsWith("rate-limit:auth:")) {
      authConfig
    } else if (key.startsWith("rate-limit:search:")) {
      searchConfig
    } else {
      generalConfig
    }
  }
}
