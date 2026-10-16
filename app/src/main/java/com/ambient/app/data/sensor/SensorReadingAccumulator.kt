package com.ambient.app.data.sensor

import com.ambient.app.domain.sensor.SensorKind
import com.ambient.app.domain.sensor.SensorReading
import com.ambient.app.domain.sensor.SensorSample
import java.util.ArrayDeque
import kotlin.math.sqrt

internal sealed interface SensorUpdate {
    data class Published(val reading: SensorReading) : SensorUpdate
    data class Invalid(val message: String) : SensorUpdate
    data object Ignored : SensorUpdate
}

/** Keeps only emitted samples; all timing comes from SensorEvent.timestamp. */
internal class SensorReadingAccumulator(
    private val kind: SensorKind,
    private val intervalNanos: Long = 100_000_000L,
    private val sampleLimit: Int = 60,
) {
    private val samples = ArrayDeque<SensorSample>()
    private var lastSeenTimestamp: Long? = null
    private var lastPublishedTimestamp: Long? = null
    private var lastPublishedLightValue: Float? = null
    private var lastPublishedAccuracy: Int? = null

    init {
        require(intervalNanos > 0)
        require(sampleLimit > 0)
    }

    fun accept(rawValues: FloatArray, timestampNanos: Long, accuracy: Int?): SensorUpdate {
        val componentCount = when (kind) {
            SensorKind.LIGHT, SensorKind.PRESSURE -> 1
            else -> 3
        }
        if (timestampNanos <= 0 || rawValues.size < componentCount ||
            (0 until componentCount).any { !rawValues[it].isFinite() }
        ) {
            reset()
            return SensorUpdate.Invalid("传感器返回了无效数据，等待下一次真实读数")
        }
        // Older or repeated hardware events must not move the graph backwards.
        if (lastSeenTimestamp?.let { timestampNanos <= it } == true) return SensorUpdate.Ignored
        lastSeenTimestamp = timestampNanos
        if (kind == SensorKind.LIGHT) {
            // LIGHT uses on-change reporting. Dropping its final change could leave the UI
            // permanently showing the previous value because the HAL need not emit again.
            if (lastPublishedLightValue == rawValues[0] && lastPublishedAccuracy == accuracy) {
                return SensorUpdate.Ignored
            }
        } else {
            if (lastPublishedTimestamp?.let { timestampNanos - it < intervalNanos } == true) {
                return SensorUpdate.Ignored
            }
        }

        val values = rawValues.take(componentCount)
        val magnitude = if (componentCount == 3) {
            sqrt(values.sumOf { it.toDouble() * it.toDouble() }).toFloat()
        } else {
            null
        }
        if (magnitude != null && !magnitude.isFinite()) {
            reset()
            return SensorUpdate.Invalid("传感器读数超出可显示范围，等待下一次真实读数")
        }
        val graphValue = magnitude ?: values.first()
        samples.addLast(SensorSample(timestampNanos, graphValue))
        while (samples.size > sampleLimit) samples.removeFirst()
        lastPublishedTimestamp = timestampNanos
        if (kind == SensorKind.LIGHT) lastPublishedLightValue = values.first()
        lastPublishedAccuracy = accuracy
        return SensorUpdate.Published(
            SensorReading(
                values = values,
                magnitude = magnitude,
                timestampNanos = timestampNanos,
                accuracy = accuracy,
                samples = samples.toList(),
                isListening = true,
            ),
        )
    }

    fun reset() {
        samples.clear()
        lastSeenTimestamp = null
        lastPublishedTimestamp = null
        lastPublishedLightValue = null
        lastPublishedAccuracy = null
    }
}
