package com.ambient.app.ui.screens

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max

/** Pure chart geometry: time remains proportional and no samples are interpolated. */
internal object HistoryTrendGeometry {
    fun xFraction(first: Instant, last: Instant, time: Instant): Float {
        val total = secondsBetween(first, last)
        if (total <= 0.0) return .5f
        return (secondsBetween(first, time) / total).coerceIn(0.0, 1.0).toFloat()
    }

    fun yRange(values: List<Double>): ClosedFloatingPointRange<Double> {
        val finite = values.filter { it.isFinite() }
        if (finite.isEmpty()) return 0.0..1.0
        val minimum = finite.min()
        val maximum = finite.max()
        if (minimum != maximum) return minimum..maximum
        val padding = max(abs(minimum) * .05, .5)
        val low = (minimum - padding).takeIf { it.isFinite() } ?: minimum
        val high = (maximum + padding).takeIf { it.isFinite() } ?: maximum
        return low..high
    }

    fun yFraction(value: Double, range: ClosedFloatingPointRange<Double>): Float {
        if (!value.isFinite() || !range.start.isFinite() || !range.endInclusive.isFinite() || range.start > range.endInclusive) return .5f
        if (range.start == range.endInclusive) return .5f
        if (value <= range.start) return 0f
        if (value >= range.endInclusive) return 1f
        val fullSpan = range.endInclusive - range.start
        val fraction = if (fullSpan.isFinite() && fullSpan > 0) (value - range.start) / fullSpan
            // Halving first also handles a finite range whose full subtraction would overflow.
            else (value / 2 - range.start / 2) / (range.endInclusive / 2 - range.start / 2)
        return if (fraction.isFinite()) fraction.coerceIn(0.0, 1.0).toFloat() else .5f
    }

    private fun secondsBetween(first: Instant, last: Instant): Double =
        Duration.between(first, last).let { it.seconds.toDouble() + it.nano / 1_000_000_000.0 }
}
