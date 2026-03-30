package modules

import com.dimafeng.testcontainers.MySQLContainer
import org.scalatest.CancelAfterFailure
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import services.ElasticsearchService
import org.testcontainers.DockerClientFactory

import java.sql.DriverManager
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Using
import scala.util.Try

class DatabaseInitializerIntegrationSpec extends PlaySpec with CancelAfterFailure {

  implicit val ec: ExecutionContext = ExecutionContext.global

  "DatabaseInitializer" must {
    "run Flyway migrations against a fresh MySQL instance" in {
      dockerAvailability() match {
        case Left(reason) =>
          cancel(s"Docker/Testcontainers unavailable for integration test: $reason")
        case Right(()) =>
          withMysqlContainer { mysql =>
            val esService = new StubElasticsearchService
            val config = Configuration.from(Map(
              "markko.database.url" -> mysql.jdbcUrl,
              "markko.database.user" -> mysql.username,
              "markko.database.password" -> mysql.password,
              "markko.database.pool-size" -> 1,
              "markko.elasticsearch.host" -> "localhost",
              "markko.elasticsearch.port" -> 9200,
              "markko.elasticsearch.scheme" -> "http",
              "markko.elasticsearch.username" -> "elastic",
              "markko.elasticsearch.password" -> "elastic"
            ))

            new DatabaseInitializer(config, esService)

            esService.ensureIndexCalled mustBe true
            tableNames(mysql) must contain allOf (
              "users",
              "links",
              "collections",
              "tags",
              "link_tags",
              "export_jobs",
              "flyway_schema_history"
            )
            appliedMigrationCount(mysql) mustBe 5
          }
      }
    }
  }

  private def dockerAvailability(): Either[String, Unit] =
    Try {
      DockerClientFactory.instance().client()
      ()
    }.toEither.left.map(_.getMessage)

  private def withMysqlContainer[A](f: MySQLContainer => A): A = {
    val mysql = MySQLContainer(
      databaseName = "markko",
      username = "markko",
      password = "markko_secret"
    )
    mysql.start()
    try f(mysql)
    finally mysql.stop()
  }

  private def tableNames(mysql: MySQLContainer): Set[String] =
    Using.resource(DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password)) { conn =>
      Using.resource(conn.createStatement()) { stmt =>
        Using.resource(stmt.executeQuery("SHOW TABLES")) { rs =>
          Iterator.continually(rs.next()).takeWhile(identity).map(_ => rs.getString(1)).toSet
        }
      }
    }

  private def appliedMigrationCount(mysql: MySQLContainer): Int =
    Using.resource(DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password)) { conn =>
      Using.resource(conn.prepareStatement("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1")) { stmt =>
        Using.resource(stmt.executeQuery()) { rs =>
          rs.next()
          rs.getInt(1)
        }
      }
    }

  private class StubElasticsearchService extends ElasticsearchService(
    Configuration.from(Map(
      "markko.elasticsearch.host" -> "localhost",
      "markko.elasticsearch.port" -> 9200,
      "markko.elasticsearch.scheme" -> "http",
      "markko.elasticsearch.username" -> "elastic",
      "markko.elasticsearch.password" -> "elastic"
    ))
  ) {
    var ensureIndexCalled = false

    override def ensureIndex(): Future[Unit] = {
      ensureIndexCalled = true
      Future.successful(())
    }
  }
}
