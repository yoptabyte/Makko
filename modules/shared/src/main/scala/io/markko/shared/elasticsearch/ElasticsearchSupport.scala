package io.markko.shared.elasticsearch

import com.sksamuel.elastic4s.{ElasticClient, ElasticProperties}
import com.sksamuel.elastic4s.http.JavaClient
import com.typesafe.config.Config
import org.apache.http.auth.{AuthScope, UsernamePasswordCredentials}
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import org.elasticsearch.client.RestClientBuilder

object ElasticsearchSupport {
  def client(config: Config): ElasticClient = {
    val host = config.getString("markko.elasticsearch.host")
    val port = config.getInt("markko.elasticsearch.port")
    val scheme = config.getString("markko.elasticsearch.scheme")
    val username = optionalString(config, "markko.elasticsearch.username")
    val password = optionalString(config, "markko.elasticsearch.password")

    val props = ElasticProperties(s"$scheme://$host:$port")

    (username, password) match {
      case (Some(user), Some(pass)) =>
        val credentials = new BasicCredentialsProvider()
        credentials.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(user, pass))

        val callback = new RestClientBuilder.HttpClientConfigCallback {
          override def customizeHttpClient(httpClientBuilder: HttpAsyncClientBuilder): HttpAsyncClientBuilder =
            httpClientBuilder.setDefaultCredentialsProvider(credentials)
        }

        ElasticClient(JavaClient(props, callback))
      case _ =>
        ElasticClient(JavaClient(props))
    }
  }

  private def optionalString(config: Config, path: String): Option[String] =
    if (config.hasPath(path)) Option(config.getString(path)).map(_.trim).filter(_.nonEmpty)
    else None
}
