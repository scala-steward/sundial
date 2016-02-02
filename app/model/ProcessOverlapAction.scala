package model

sealed trait ProcessOverlapAction
object ProcessOverlapAction {
  case object Wait extends ProcessOverlapAction
  case object Terminate extends ProcessOverlapAction
}
