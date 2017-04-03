package controllers

import javax.inject.Inject

import org.lyranthe.prometheus.client.Registry
import play.api.mvc.{Action, Controller}

class Healthchecks @Inject() (prometheusRegistry: Registry) extends Controller {

  def getMetrics = Action {
    Ok(prometheusRegistry.outputText)
  }

}
