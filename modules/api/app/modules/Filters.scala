package modules

import javax.inject._
import play.api.mvc._
import play.api.http.HttpFilters
import filters.RateLimitFilter
import play.filters.cors.CORSFilter
import play.filters.gzip.GzipFilter

@Singleton
class Filters @Inject()(
  corsFilter: CORSFilter,
  rateLimitFilter: RateLimitFilter,
  gzipFilter: GzipFilter
) extends HttpFilters {

  override def filters: Seq[EssentialFilter] = Seq(
    corsFilter,
    gzipFilter,
    rateLimitFilter
  )
}
