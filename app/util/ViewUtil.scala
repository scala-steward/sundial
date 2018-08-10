package util

object ViewUtil {

  def radius[T <: Number](value: T,
                          otherValues: Seq[T],
                          bufferPct: Double,
                          minRadius: Double,
                          maxRadius: Double) = {
    // If there are no other values, or if value > max of values, include the value in values
    val values =
      if (otherValues.isEmpty || value.doubleValue > otherValues
            .map(_.doubleValue)
            .max) {
        value +: otherValues
      } else {
        otherValues
      }
    val minArea = Math.PI * minRadius * minRadius
    val maxArea = Math.PI * maxRadius * maxRadius
    val meanValue = values.map(_.doubleValue).sum / values.length.toDouble
    val minValue =
      Math.min(values.map(_.doubleValue).min, (1 - bufferPct) * meanValue)
    val maxValue =
      Math.max(values.map(_.doubleValue).max, (1 + bufferPct) * meanValue)
    val areaProportion = (value.doubleValue - minValue) / (maxValue - minValue)
    val targetArea = areaProportion * (maxArea - minArea) + minArea
    val targetRadius = Math.sqrt(targetArea / Math.PI)

    targetRadius
  }

}
