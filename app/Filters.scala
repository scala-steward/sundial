import com.google.inject.Inject
import org.lyranthe.prometheus.client.integration.play.filters.PrometheusFilter
import play.api.http.HttpFilters

class Filters @Inject()(prometheusFilter: PrometheusFilter)
    extends HttpFilters {

  val filters = Seq(prometheusFilter)

}
