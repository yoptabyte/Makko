package io.markko.shared.redis

import com.typesafe.config.Config
import io.markko.shared.config.ConfigOps

object RedisKeys {
  val ParseQueue          = "queue:parse"
  val ExportQueue         = "queue:export"
  val DeleteQueue         = "queue:delete"
  val ParsedEventsChannel = "notifications:parsed"

  def dedupKey(urlHash: String): String = s"link:hash:$urlHash"
  def previewKey(linkId: Long): String  = s"preview:$linkId"
}

object RedisConfigSupport {
  def connectionUri(config: Config): String = {
    val password = ConfigOps.optionalString(config, "markko.redis.password")
    val baseUri = ConfigOps.optionalString(config, "markko.redis.uri").getOrElse {
      val host = ConfigOps.optionalString(config, "markko.redis.host").getOrElse("localhost")
      val port = if (config.hasPath("markko.redis.port")) config.getInt("markko.redis.port") else 6379

      password match {
        case Some(value) => s"redis://:$value@$host:$port"
        case None        => s"redis://$host:$port"
      }
    }

    injectPassword(baseUri, password)
  }

  private def injectPassword(uri: String, password: Option[String]): String =
    password match {
      case Some(value) if !uri.contains("@") =>
        uri.split("://", 2) match {
          case Array(scheme, rest) => s"$scheme://:$value@$rest"
          case _                   => uri
        }
      case _ => uri
    }
}
