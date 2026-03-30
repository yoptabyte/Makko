package modules

import javax.inject._
import com.google.inject.{AbstractModule, Provides}
import play.api.Configuration
import cats.effect._
import cats.effect.unsafe.implicits.global
import doobie._
import doobie.hikari.HikariTransactor
import org.flywaydb.core.Flyway
import services.ElasticsearchService
import com.typesafe.scalalogging.LazyLogging
import java.nio.file.Paths

import scala.concurrent.ExecutionContext

/** Guice module: wires up Doobie transactor, runs Flyway migrations, initializes ES index */
class DatabaseModule extends AbstractModule with LazyLogging {

  override def configure(): Unit = {
    // Eager binding ensures migration runs on startup
    bind(classOf[DatabaseInitializer]).asEagerSingleton()
  }

  @Provides
  @Singleton
  def transactor(config: Configuration): Transactor[IO] = {
    val dbUrl      = config.get[String]("markko.database.url")
    val dbUser     = config.get[String]("markko.database.user")
    val dbPassword = config.get[String]("markko.database.password")
    val poolSize   = config.get[Int]("markko.database.pool-size")

    HikariTransactor.newHikariTransactor[IO](
      driverClassName = "com.mysql.cj.jdbc.Driver",
      url             = dbUrl,
      user            = dbUser,
      pass            = dbPassword,
      connectEC       = ExecutionContext.global
    ).allocated.unsafeRunSync()._1
  }
}

@Singleton
class DatabaseInitializer @Inject()(
  config:    Configuration,
  esService: ElasticsearchService
) extends LazyLogging {

  // Run Flyway migrations
  private val dbUrl      = config.get[String]("markko.database.url")
  private val dbUser     = config.get[String]("markko.database.user")
  private val dbPassword = config.get[String]("markko.database.password")
  private val migrationLocations = {
    val classpathLocation = "classpath:db/migration"
    val classpathMarker = Option(getClass.getClassLoader.getResource("db/migration/V1__create_users.sql"))
    val sourceDir = Paths.get("modules/api/src/main/resources/db/migration").toAbsolutePath.normalize

    if (classpathMarker.isDefined) Seq(classpathLocation)
    else if (sourceDir.toFile.isDirectory) Seq(s"filesystem:${sourceDir.toString}")
    else Seq(classpathLocation)
  }

  logger.info(s"Running Flyway database migrations from: ${migrationLocations.mkString(", ")}")
  Flyway
    .configure()
    .dataSource(dbUrl, dbUser, dbPassword)
    .locations(migrationLocations: _*)
    .load()
    .migrate()
  logger.info("Flyway migrations complete.")

  // Ensure ES index exists
  logger.info("Ensuring Elasticsearch index exists...")
  esService.ensureIndex()
  logger.info("Elasticsearch index ready.")
}
