package controllers

import common.SundialGlobal
import dao.SundialDao
import play.api.mvc.Controller

/**
 * Defines utility methods for Sundial controllers.
 */
abstract class SundialController extends Controller {

  def withSundialDao[T](f: SundialDao => T) = SundialGlobal.dependencies.daoFactory.withSundialDao(f)

}
