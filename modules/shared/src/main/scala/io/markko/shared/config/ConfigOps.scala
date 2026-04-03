package io.markko.shared.config

import com.typesafe.config.Config

object ConfigOps {
  def optionalString(config: Config, path: String): Option[String] =
    if (config.hasPath(path)) Option(config.getString(path)).map(_.trim).filter(_.nonEmpty)
    else None
}
