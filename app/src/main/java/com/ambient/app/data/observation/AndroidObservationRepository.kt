package com.ambient.app.data.observation

import android.content.Context
import android.hardware.*
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.ambient.app.domain.observation.*
import com.ambient.app.domain.sensor.SensorCapability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns only the selected sensor, after an explicit Start. Aggregation runs on a worker looper. */
class AndroidObservationRepository(context: Context) : ObservationRepository {
    private val manager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensors = ObservationKind.entries.associateWith { manager?.getDefaultSensor(when(it) {
        ObservationKind.LIGHT -> Sensor.TYPE_LIGHT; ObservationKind.MAGNETIC -> Sensor.TYPE_MAGNETIC_FIELD
        ObservationKind.PRESSURE -> Sensor.TYPE_PRESSURE
    }) }
    override val capabilities = sensors.mapValues { (_, sensor) -> SensorCapability(sensor != null,
        sensor?.name, sensor?.vendor, sensor?.resolution, sensor?.maximumRange) }
    private val mutable = MutableStateFlow(ObservationResult())
    override val state = mutable.asStateFlow()
    private val lock = Any()
    private var active: Session? = null
    private class Session(val thread: HandlerThread, val handler: Handler, val accumulator: ObservationAccumulator) {
        lateinit var listener: SensorEventListener
    }
    override fun start(kind: ObservationKind, seconds: Int, name: String) = synchronized(lock) {
        if (active != null) return@synchronized
        require(seconds == 30 || seconds == 60)
        val sensor = sensors[kind]
        if (sensor == null || manager == null) {
            mutable.value = ObservationResult(kind, seconds, name.take(30), status = ObservationStatus.ERROR,
                message = "设备不支持此传感器，无法观察。")
            return@synchronized
        }
        val started = SystemClock.elapsedRealtimeNanos()
        val thread = HandlerThread("AmbientObservation").apply { start() }
        val session = Session(thread, Handler(thread.looper), ObservationAccumulator(kind, started, seconds, name.take(30)))
        session.listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) = synchronized(lock) {
                if (active !== session) return@synchronized
                val now = SystemClock.elapsedRealtimeNanos()
                // Late delivery after the deadline is not silently inserted into a closed observation.
                if (now >= session.accumulator.deadline) { finish(session, now, "已到记录时长"); return@synchronized }
                session.accumulator.accept(event.timestamp, event.values, event.accuracy, now)
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        active = session
        val registered = try { manager.registerListener(session.listener, sensor, 50_000, session.handler) }
            catch (_: RuntimeException) { false }
        if (!registered) {
            finish(session, started, "设备未能启动传感器，请重试。", error = true)
            return@synchronized
        }
        mutable.value = session.accumulator.snapshot(started)
        val tick = object : Runnable {
            override fun run() = synchronized(lock) {
                if (active !== session) return@synchronized
                val now = SystemClock.elapsedRealtimeNanos()
                if (now >= session.accumulator.deadline) finish(session, now, "已到记录时长")
                else { mutable.value = session.accumulator.snapshot(now); session.handler.postDelayed(this, 500) }
                Unit
            }
        }
        session.handler.post(tick)
        Unit
    }
    override fun stop(reason: String) = synchronized(lock) {
        active?.let { finish(it, SystemClock.elapsedRealtimeNanos(), reason) }
        Unit
    }
    private fun finish(session: Session, now: Long, reason: String, error: Boolean = false) {
        if (active !== session) return
        active = null
        manager?.unregisterListener(session.listener)
        session.handler.removeCallbacksAndMessages(null)
        session.thread.quitSafely()
        val status = when {
            error -> ObservationStatus.ERROR
            now >= session.accumulator.deadline -> ObservationStatus.COMPLETED
            else -> ObservationStatus.INTERRUPTED
        }
        mutable.value = session.accumulator.snapshot(now, status, reason)
    }
}
