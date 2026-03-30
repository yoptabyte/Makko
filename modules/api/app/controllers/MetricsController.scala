package controllers

import javax.inject._
import play.api.mvc._
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import java.io.StringWriter

@Singleton
class MetricsController @Inject()(
  cc: ControllerComponents
) extends AbstractController(cc) {

  /** GET /metrics — Prometheus metrics endpoint */
  def metrics(): Action[AnyContent] = Action {
    val writer = new StringWriter()
    TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples())
    Ok(writer.toString).as(TextFormat.CONTENT_TYPE_004)
  }
}
