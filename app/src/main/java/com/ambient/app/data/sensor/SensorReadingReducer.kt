package com.ambient.app.data.sensor

import com.ambient.app.domain.sensor.SensorReading

/** Acquisition failures and platform accuracy reports have different meanings. */
internal object SensorReadingReducer {
    fun onSample(current: SensorReading, update: SensorUpdate, accuracy: Int): SensorReading = when (update) {
        is SensorUpdate.Published -> update.reading.copy(
            eventAccuracy = accuracy,
            callbackAccuracy = current.callbackAccuracy,
        )
        is SensorUpdate.Invalid -> SensorReading(
            isListening = true,
            accuracy = accuracy,
            error = update.message,
            eventAccuracy = accuracy,
            callbackAccuracy = current.callbackAccuracy,
        )
        // Includes out-of-order events. Do not let their status replace newer information.
        SensorUpdate.Ignored -> current
    }

    fun onAccuracyChanged(current: SensorReading, accuracy: Int): SensorReading = current.copy(
        accuracy = accuracy,
        callbackAccuracy = accuracy,
        // An accuracy callback alone cannot recover a missing/invalid measurement.
    )
}
