package io.markko.shared.config

/** Database connection config */
case class DatabaseConfig(
  url:      String,
  user:     String,
  password: String,
  poolSize: Int
)

/** Redis connection config */
case class RedisConfig(
  uri:      String,
  password: Option[String]
)

/** Elasticsearch connection config */
case class ElasticsearchConfig(
  host:     String,
  port:     Int,
  scheme:   String,
  username: String,
  password: String
)

/** Obsidian vault path config */
case class VaultConfig(
  basePath: String
)

/** Top-level app config */
case class MarkkoConfig(
  database:      DatabaseConfig,
  redis:         RedisConfig,
  elasticsearch: ElasticsearchConfig,
  vault:         VaultConfig
)
