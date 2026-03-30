package services

import javax.inject._
import play.api.Configuration
import play.api.libs.json._
import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.fields.{DateField, KeywordField, LongField, TextField}
import io.markko.shared.elasticsearch.ElasticsearchSupport

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ElasticsearchService @Inject()(
  config: Configuration
)(implicit ec: ExecutionContext) {

  private val host     = config.get[String]("markko.elasticsearch.host")
  private val port     = config.get[Int]("markko.elasticsearch.port")
  private val scheme   = config.get[String]("markko.elasticsearch.scheme")
  private val username = config.get[String]("markko.elasticsearch.username")
  private val password = config.get[String]("markko.elasticsearch.password")

  val client = ElasticsearchSupport.client(config.underlying)

  private val indexName = "markko-links"

  /** Create the index if it doesn't exist */
  def ensureIndex(): Future[Unit] = {
    val existsResp = client.execute {
      indexExists(indexName)
    }.await

    if (!existsResp.result.isExists) {
      client.execute {
        createIndex(indexName).mapping(
          properties(
            TextField("title", boost = Some(2.0)),
            TextField("content"),
            KeywordField("url"),
            KeywordField("tags"),
            KeywordField("collection"),
            KeywordField("status"),
            LongField("userId"),
            DateField("savedAt"),
            DateField("parsedAt")
          )
        )
      }.await
    }

    Future.successful(())
  }

  /** Index a link document */
  def indexLink(
    linkId:     Long,
    userId:     Long,
    url:        String,
    title:      String,
    content:    String,
    tags:       List[String],
    collection: Option[String],
    savedAt:    String
  ): Future[Unit] = Future {
    client.execute {
      indexInto(indexName)
        .id(linkId.toString)
        .fields(
          "title"      -> title,
          "content"    -> content,
          "url"        -> url,
          "tags"       -> tags,
          "collection" -> collection.getOrElse(""),
          "userId"     -> userId,
          "savedAt"    -> savedAt
        )
    }.await
    ()
  }

  /** Full-text search across title and content */
  def search(query: String, userId: Long, limit: Int = 20): Future[List[JsObject]] = Future {
    val resp = client.execute {
      com.sksamuel.elastic4s.ElasticDsl.search(indexName)
        .query(userScopedQuery(userId, Seq(
          multiMatchQuery(query).fields("title^2", "content", "tags")
        )))
        .size(limit)
        .highlighting(
          highlight("title").preTag("<mark>").postTag("</mark>"),
          highlight("content").preTag("<mark>").postTag("</mark>").fragmentSize(200)
        )
    }.await

    resp.result.hits.hits.toList.map { hit =>
      val source = hit.sourceAsMap
      Json.obj(
        "id"         -> JsNumber(BigDecimal(hit.id)),
        "title"      -> JsString(source.getOrElse("title", "").toString),
        "url"        -> JsString(source.getOrElse("url", "").toString),
        "tags"       -> Json.toJson(source.getOrElse("tags", List.empty).asInstanceOf[List[String]]),
        "collection" -> JsString(source.getOrElse("collection", "").toString),
        "highlights" -> {
          val titleHL   = hit.highlight.getOrElse("title", Nil)
          val contentHL = hit.highlight.getOrElse("content", Nil)
          Json.obj(
            "title"   -> titleHL.headOption,
            "content" -> contentHL.headOption
          )
        }
      )
    }
  }

  /** Delete a link from the index */
  def deleteLink(linkId: Long): Future[Unit] = Future {
    client.execute {
      deleteById(indexName, linkId.toString)
    }.await
    ()
  }

  /** Search by specific tag */
  def searchByTag(tag: String, userId: Long, limit: Int = 50): Future[List[JsObject]] = Future {
    val resp = client.execute {
      com.sksamuel.elastic4s.ElasticDsl.search(indexName)
        .query(userScopedQuery(userId, Seq(termQuery("tags", tag))))
        .size(limit)
        .sortByFieldDesc("savedAt")
    }.await

    resp.result.hits.hits.toList.map { hit =>
      val source = hit.sourceAsMap
      Json.obj(
        "id"         -> JsNumber(BigDecimal(hit.id)),
        "title"      -> JsString(source.getOrElse("title", "").toString),
        "url"        -> JsString(source.getOrElse("url", "").toString),
        "tags"       -> Json.toJson(source.getOrElse("tags", List.empty).asInstanceOf[List[String]]),
        "collection" -> JsString(source.getOrElse("collection", "").toString)
      )
    }
  }

  /** Advanced search with filters */
  def searchAdvanced(
    query:      Option[String],
    tags:       List[String] = Nil,
    collection: Option[String] = None,
    userId:     Long,
    limit:      Int = 20,
    offset:     Int = 0
  ): Future[SearchResult] = Future {
    val queries = scala.collection.mutable.ListBuffer[com.sksamuel.elastic4s.requests.searches.queries.Query]()

    query.foreach { q =>
      queries += multiMatchQuery(q).fields("title^2", "content", "tags")
    }
    tags.foreach { tag =>
      queries += termQuery("tags", tag)
    }
    collection.foreach { col =>
      queries += termQuery("collection", col)
    }

    val finalQuery = userScopedQuery(userId, queries.toSeq)

    val resp = client.execute {
      com.sksamuel.elastic4s.ElasticDsl.search(indexName)
        .query(finalQuery)
        .from(offset)
        .size(limit)
        .sortByFieldDesc("savedAt")
        .highlighting(
          highlight("title").preTag("<mark>").postTag("</mark>"),
          highlight("content").preTag("<mark>").postTag("</mark>").fragmentSize(200)
        )
    }.await

    val hits = resp.result.hits.hits.toList.map { hit =>
      val source = hit.sourceAsMap
      Json.obj(
        "id"         -> JsNumber(BigDecimal(hit.id)),
        "title"      -> JsString(source.getOrElse("title", "").toString),
        "url"        -> JsString(source.getOrElse("url", "").toString),
        "tags"       -> Json.toJson(source.getOrElse("tags", List.empty).asInstanceOf[List[String]]),
        "collection" -> JsString(source.getOrElse("collection", "").toString),
        "highlights" -> {
          val titleHL   = hit.highlight.getOrElse("title", Nil)
          val contentHL = hit.highlight.getOrElse("content", Nil)
          Json.obj(
            "title"   -> titleHL.headOption,
            "content" -> contentHL.headOption
          )
        }
      )
    }

    SearchResult(
      total  = resp.result.totalHits,
      hits   = hits,
      offset = offset,
      limit  = limit
    )
  }

  /** Autocomplete suggestions based on title prefix */
  def suggest(prefix: String, userId: Long, limit: Int = 5): Future[List[JsObject]] = Future {
    val resp = client.execute {
      com.sksamuel.elastic4s.ElasticDsl.search(indexName)
        .query(userScopedQuery(userId, Seq(matchPhrasePrefixQuery("title", prefix))))
        .size(limit)
    }.await

    resp.result.hits.hits.toList.map { hit =>
      val source = hit.sourceAsMap
      Json.obj(
        "id"    -> JsNumber(BigDecimal(hit.id)),
        "title" -> JsString(source.getOrElse("title", "").toString),
        "url"   -> JsString(source.getOrElse("url", "").toString)
      )
    }
  }

  private def userScopedQuery(
    userId: Long,
    mustQueries: Seq[com.sksamuel.elastic4s.requests.searches.queries.Query]
  ) = {
    val scoped = boolQuery().filter(termQuery("userId", userId))
    if (mustQueries.isEmpty) scoped.must(matchAllQuery())
    else scoped.must(mustQueries)
  }
}

/** Search result with pagination info */
case class SearchResult(
  total:  Long,
  hits:   List[JsObject],
  offset: Int,
  limit:  Int
)
