package com.ambient.app.domain.observation

import com.ambient.app.domain.sensor.SensorCapability
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

enum class ObservationKind(val label: String, val unit: String) {
    LIGHT("光照", "lx"), MAGNETIC("地磁场", "μT"), PRESSURE("气压", "hPa")
}
enum class ObservationStatus { IDLE, RUNNING, COMPLETED, INTERRUPTED, ERROR }
data class ObservationBin(val timeNanos: Long, val value: Double, val min: Double, val max: Double)
data class ObservationResult(
    val kind: ObservationKind = ObservationKind.LIGHT, val durationSeconds: Int = 30, val name: String = "",
    val status: ObservationStatus = ObservationStatus.IDLE, val startedNanos: Long = 0, val endedNanos: Long = 0,
    val count: Long = 0, val rejected: Long = 0, val unreliable: Long = 0,
    val minimum: Double? = null, val maximum: Double? = null, val mean: Double? = null,
    val values: List<Float> = emptyList(), val eventNanos: Long? = null,
    val bins: List<ObservationBin> = emptyList(), val message: String? = null,
) {
    val elapsedSeconds get() = ((endedNanos - startedNanos).coerceAtLeast(0) / 1_000_000_000.0).coerceAtMost(durationSeconds.toDouble())
    val canCompare get() = status == ObservationStatus.COMPLETED && count > 0
}
interface ObservationRepository {
    val capabilities: Map<ObservationKind, SensorCapability>
    val state: StateFlow<ObservationResult>
    fun start(kind: ObservationKind, seconds: Int, name: String)
    fun stop(reason: String)
}

/** All valid hardware events contribute to statistics; only the display curve is bucketed. */
class ObservationAccumulator(val kind: ObservationKind, val started: Long, val seconds: Int, private val name: String = "") {
    init { require(started >= 0 && seconds in listOf(30, 60)) }
    val deadline = started + seconds * 1_000_000_000L
    private var count = 0L
    private var rejected = 0L
    private var unreliable = 0L
    private var lastTime: Long? = null
    private var values = emptyList<Float>()
    private var min: Double? = null
    private var max: Double? = null
    private var mean = 0.0
    private val bins = mutableListOf<ObservationBin>()

    fun accept(timestamp: Long, raw: FloatArray, accuracy: Int, receivedAt: Long) {
        if (timestamp < started || timestamp > deadline || timestamp > receivedAt || timestamp <= (lastTime ?: -1)) {
            rejected++; return
        }
        val size = if (kind == ObservationKind.MAGNETIC) 3 else 1
        if (raw.size < size || (0 until size).any { !raw[it].isFinite() } ||
            (kind == ObservationKind.LIGHT && raw[0] < 0) || (kind == ObservationKind.PRESSURE && raw[0] <= 0)) {
            rejected++; return
        }
        val value = if (size == 3) sqrt((0 until size).sumOf { raw[it].toDouble() * raw[it] }) else raw[0].toDouble()
        count++; if (accuracy == 0) unreliable++
        min = min?.coerceAtMost(value) ?: value; max = max?.coerceAtLeast(value) ?: value
        mean += (value - mean) / count
        lastTime = timestamp; values = raw.take(size)
        val bin = (timestamp - started) / 500_000_000L
        val previous = bins.lastOrNull()
        if (previous != null && (previous.timeNanos - started) / 500_000_000L == bin)
            bins[bins.lastIndex] = ObservationBin(timestamp, value, previous.min.coerceAtMost(value), previous.max.coerceAtLeast(value))
        else bins += ObservationBin(timestamp, value, value, value)
    }
    fun snapshot(now: Long, status: ObservationStatus = ObservationStatus.RUNNING, message: String? = null) = ObservationResult(
        kind, seconds, name, status, started, now.coerceIn(started, deadline), count, rejected, unreliable,
        min, max, if (count == 0L) null else mean, values.toList(), lastTime, bins.toList(), message)
}
