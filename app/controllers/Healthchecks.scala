package controllers

import javax.inject.Inject
import org.lyranthe.prometheus.client.Registry
import play.api.mvc.InjectedController

class Healthchecks @Inject()(prometheusRegistry: Registry)
    extends InjectedController {

  def getMetrics = Action {
    Ok(prometheusRegistry.outputText)
  }

}
