package com.ambient.app.data.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.ambient.app.domain.sensor.AccelerationMode
import com.ambient.app.domain.sensor.SensorCapability
import com.ambient.app.domain.sensor.SensorFeed
import com.ambient.app.domain.sensor.SensorKind
import com.ambient.app.domain.sensor.SensorReading
import com.ambient.app.domain.sensor.SensorRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidSensorRepository(context: Context) : SensorRepository {
    private val manager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val lock = Any()
    private val records = SensorKind.entries.map { kind ->
        val sensor = manager?.getDefaultSensor(kind.androidType)
        SensorRecord(kind, sensor)
    }
    private val recordsByType = records.associateBy { it.kind.androidType }
    private var activeSession: MonitoringSession? = null
    private var accelerationMode = AccelerationMode.LINEAR

    override val feeds: List<SensorFeed> = records.map { record ->
        val sensor = record.sensor
        SensorFeed(
            kind = record.kind,
            capability = SensorCapability(
                available = sensor != null,
                name = sensor?.name,
                vendor = sensor?.vendor,
                resolution = sensor?.resolution,
                maximumRange = sensor?.maximumRange,
            ),
            state = record.state.asStateFlow(),
        )
    }

    override fun setAccelerationMode(mode: AccelerationMode) = synchronized(lock) {
        if (accelerationMode == mode) return@synchronized
        val previousKind = accelerationMode.kind
        accelerationMode = mode
        val session = activeSession ?: return@synchronized
        val listener = session.listener ?: return@synchronized
        val previous = records.first { it.kind == previousKind }
        session.registeredKinds.remove(previousKind)
        session.accumulators.remove(previousKind)
        session.startedAtNanos.remove(previousKind)
        previous.sensor?.let { manager?.unregisterListener(listener, it) }
        previous.state.value = SensorReading()
        // Replace only acceleration; other sensors keep their event history and subscriptions.
        registerRecord(records.first { it.kind == mode.kind }, session)
    }

    override fun start() = synchronized(lock) {
        if (activeSession != null || manager == null || records.none { it.sensor != null }) return@synchronized
        val thread = HandlerThread("AmbientSensors").apply { start() }
        val session = MonitoringSession(thread, Handler(thread.looper))
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) = synchronized(lock) sensorEvent@{
                if (activeSession !== session) return@sensorEvent
                val record = recordsByType[event.sensor.type] ?: return@sensorEvent
                if (record.kind !in session.registeredKinds) return@sensorEvent
                if (record.kind.isAcceleration && event.timestamp < session.startedAtNanos.getValue(record.kind)) return@sensorEvent
                val update = session.accumulators.getValue(record.kind)
                    .accept(event.values, event.timestamp, event.accuracy)
                record.state.value = SensorReadingReducer.onSample(record.state.value, update, event.accuracy)
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = synchronized(lock) accuracyEvent@{
                if (activeSession !== session) return@accuracyEvent
                val record = recordsByType[sensor.type] ?: return@accuracyEvent
                if (record.kind !in session.registeredKinds) return@accuracyEvent
                record.state.value = SensorReadingReducer.onAccuracyChanged(record.state.value, accuracy)
            }
        }
        session.listener = listener
        activeSession = session
        records.forEach { record ->
            record.state.value = SensorReading()
            if (!record.kind.isAcceleration || record.kind == accelerationMode.kind) registerRecord(record, session)
        }
        if (session.registeredKinds.isEmpty()) {
            activeSession = null
            session.handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }
    }

    /** Called under [lock], including when changing acceleration mode within an active session. */
    private fun registerRecord(record: SensorRecord, session: MonitoringSession) {
        record.state.value = SensorReading()
        val sensor = record.sensor ?: return
        val listener = session.listener ?: return
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val registered = try {
            manager?.registerListener(listener, sensor, SAMPLE_PERIOD_MICROS, session.handler) == true
        } catch (_: RuntimeException) {
            false
        }
        if (!registered) {
            record.state.value = SensorReading(error = "设备未能启动此传感器，请稍后重试")
            return
        }
        val accumulator = SensorReadingAccumulator(record.kind)
        session.registeredKinds.add(record.kind)
        session.accumulators[record.kind] = accumulator
        session.startedAtNanos[record.kind] = startedAt
        record.state.value = SensorReading(isListening = true)
        // An on-change sensor may stop emitting after its first event. A retired mode's
        // timeout must not flag a later subscription to the same acceleration feed.
        session.handler.postDelayed({
            synchronized(lock) {
                if (activeSession === session && session.accumulators[record.kind] === accumulator &&
                    record.state.value.timestampNanos == null
                ) {
                    record.state.value = record.state.value.copy(
                        error = "尚未收到有效传感器读数，请稍候或重新开始监听",
                    )
                }
            }
        }, FIRST_EVENT_TIMEOUT_MILLIS)
    }

    override fun stop() = synchronized(lock) {
        val session = activeSession
        activeSession = null
        if (session != null) {
            session.listener?.let { manager?.unregisterListener(it) }
            session.handler.removeCallbacksAndMessages(null)
            session.thread.quitSafely()
        }
        records.forEach { record ->
            // The last reading remains available as a paused reading, never as a fresh event.
            record.state.value = record.state.value.copy(isListening = false, samples = emptyList())
        }
    }

    private class SensorRecord(val kind: SensorKind, val sensor: Sensor?) {
        val state = MutableStateFlow(SensorReading())
    }

    private class MonitoringSession(val thread: HandlerThread, val handler: Handler) {
        var listener: SensorEventListener? = null
        val registeredKinds = mutableSetOf<SensorKind>()
        val accumulators = mutableMapOf<SensorKind, SensorReadingAccumulator>()
        val startedAtNanos = mutableMapOf<SensorKind, Long>()
    }

    private companion object {
        const val SAMPLE_PERIOD_MICROS = 50_000
        const val FIRST_EVENT_TIMEOUT_MILLIS = 5_000L

        val SensorKind.androidType: Int
            get() = when (this) {
                SensorKind.LIGHT -> Sensor.TYPE_LIGHT
                SensorKind.MAGNETIC_FIELD -> Sensor.TYPE_MAGNETIC_FIELD
                SensorKind.ACCELEROMETER -> Sensor.TYPE_ACCELEROMETER
                SensorKind.LINEAR_ACCELERATION -> Sensor.TYPE_LINEAR_ACCELERATION
                SensorKind.GYROSCOPE -> Sensor.TYPE_GYROSCOPE
                SensorKind.PRESSURE -> Sensor.TYPE_PRESSURE
            }
    }
}
