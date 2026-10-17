package com.ambient.app.data.orientation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.ambient.app.domain.orientation.DisplayRotation
import com.ambient.app.domain.orientation.OrientationCapability
import com.ambient.app.domain.orientation.OrientationMath
import com.ambient.app.domain.orientation.OrientationReading
import com.ambient.app.domain.orientation.OrientationRepository
import com.ambient.app.domain.orientation.OrientationSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/** A separate subscription: changing the acceleration card's mode cannot reset orientation. */
class AndroidOrientationRepository internal constructor(
    private val platform: OrientationPlatform,
    private val clockNanos: () -> Long,
) : OrientationRepository {
    constructor(context: Context) : this(AndroidOrientationPlatform(context), SystemClock::elapsedRealtimeNanos)

    private val lock = Any()
    private val sensors = platform.sensors
    override val capability = OrientationCapability(
        source = when {
            OrientationSensor.ROTATION_VECTOR in sensors -> OrientationSource.ROTATION_VECTOR
            OrientationSensor.ACCELEROMETER in sensors && OrientationSensor.MAGNETOMETER in sensors ->
                OrientationSource.ACCELEROMETER_MAGNETOMETER
            OrientationSensor.ACCELEROMETER in sensors -> OrientationSource.ACCELEROMETER_ONLY
            else -> OrientationSource.UNAVAILABLE
        },
        rotationVectorName = sensors[OrientationSensor.ROTATION_VECTOR],
        accelerometerName = sensors[OrientationSensor.ACCELEROMETER],
        magneticFieldName = sensors[OrientationSensor.MAGNETOMETER],
    )
    private val mutableState = MutableStateFlow(OrientationReading())
    override val state = mutableState.asStateFlow()
    private var rotation = DisplayRotation.R0
    private var session: MonitoringSession? = null

    override fun start() = synchronized(lock) {
        if (session != null) return@synchronized
        mutableState.value = OrientationReading(displayRotation = rotation)
        if (!capability.available) {
            mutableState.value = mutableState.value.copy(error = "此设备缺少姿态探测所需的传感器")
            return@synchronized
        }
        val current = MonitoringSession(clockNanos())
        val subscription = try {
            platform.open(
                onSample = { kind, values, timestamp, accuracy ->
                    synchronized(lock) {
                        if (session === current) onSample(current, kind, values, timestamp, accuracy)
                    }
                },
                onAccuracy = { kind, accuracy ->
                    synchronized(lock) {
                        if (session === current && kind in current.registered) {
                            current.accuracies[kind] = validAccuracy(accuracy)
                            updateAccuracy(current)
                        }
                    }
                },
            )
        } catch (_: RuntimeException) {
            mutableState.value = mutableState.value.copy(error = "设备未能启动姿态采集，请稍后重试")
            return@synchronized
        }
        current.subscription = subscription
        session = current
        val required = when (capability.source) {
            OrientationSource.ROTATION_VECTOR -> listOf(OrientationSensor.ROTATION_VECTOR)
            OrientationSource.ACCELEROMETER_MAGNETOMETER -> listOf(OrientationSensor.ACCELEROMETER, OrientationSensor.MAGNETOMETER)
            OrientationSource.ACCELEROMETER_ONLY -> listOf(OrientationSensor.ACCELEROMETER)
            OrientationSource.UNAVAILABLE -> emptyList()
        }
        for (kind in required) {
            val registered = try { subscription.register(kind) } catch (_: RuntimeException) { false }
            if (!registered) {
                session = null
                runCatching { subscription.close() }
                mutableState.value = OrientationReading(
                    displayRotation = rotation,
                    error = "设备未能启动姿态传感器，请稍后重试",
                )
                return@synchronized
            }
            current.registered += kind
        }
        mutableState.value = OrientationReading(isListening = true, displayRotation = rotation)
        subscription.postDelayed(FIRST_EVENT_TIMEOUT_MILLIS) {
            synchronized(lock) {
                if (session === current && current.latest == null) {
                    mutableState.value = mutableState.value.copy(error = "尚未收到有效姿态读数，请稍候或重新开始探测")
                }
            }
        }
        scheduleStaleCheck(current)
    }

    override fun stop() = synchronized(lock) {
        val previous = session
        session = null
        previous?.subscription?.let { runCatching { it.close() } }
        // Only a paused reading may retain the last angles. Stale readings have already cleared them.
        mutableState.value = mutableState.value.copy(isListening = false)
    }

    override fun setDisplayRotation(rotation: DisplayRotation) = synchronized(lock) {
        if (this.rotation == rotation) return@synchronized
        this.rotation = rotation
        val current = session
        val latest = current?.latest
        if (current != null && latest != null && isFresh(latest, clockNanos())) {
            publish(current, latest)
        } else {
            // A paused old value in a different screen coordinate system must not be displayed.
            mutableState.value = mutableState.value.copy(angles = null, displayRotation = rotation)
        }
    }

    private fun onSample(current: MonitoringSession, kind: OrientationSensor, values: FloatArray, timestamp: Long, accuracy: Int?) {
        if (kind !in current.registered) return
        val now = clockNanos()
        if (timestamp < current.startedAt || timestamp > now || now - timestamp > STALE_NANOS ||
            timestamp <= (current.samples[kind]?.timestamp ?: Long.MIN_VALUE)
        ) return
        val size = if (kind == OrientationSensor.ROTATION_VECTOR && values.size >= 4) 4 else 3
        if (values.size < size || values.take(size).any { !it.isFinite() }) return
        if (kind == OrientationSensor.ROTATION_VECTOR) {
            val norm = (0 until size).sumOf { values[it].toDouble() * values[it] }
            // Android also supports xyz-only vectors, deriving w = sqrt(1 - x² - y² - z²).
            if (if (size == 4) abs(norm - 1.0) > 0.05 else norm > 1.001) return
        } else if (kind == OrientationSensor.ACCELEROMETER && capability.source == OrientationSource.ACCELEROMETER_ONLY) {
            val magnitudeSquared = (0..2).sumOf { values[it].toDouble() * values[it] }
            // Near free fall there is no useful gravity direction; do not amplify tiny noise.
            if (magnitudeSquared < MIN_GRAVITY * MIN_GRAVITY) return
        }
        val sample = OrientationSample(values.copyOf(), timestamp, validAccuracy(accuracy))
        current.samples[kind] = sample
        current.accuracies[kind] = sample.accuracy
        val candidate = when (capability.source) {
            OrientationSource.ROTATION_VECTOR -> {
                val matrix = platform.rotationMatrix(sample.values) ?: return
                val headingAccuracy = sample.values.getOrNull(4)?.takeIf { it.isFinite() && it >= 0f }
                    ?.let { Math.toDegrees(it.toDouble()).toFloat() }?.takeIf(Float::isFinite)
                OrientationCandidate(matrix = matrix, timestamp = timestamp, headingAccuracy = headingAccuracy)
            }
            OrientationSource.ACCELEROMETER_MAGNETOMETER -> {
                val acceleration = current.samples[OrientationSensor.ACCELEROMETER] ?: return
                val magnetic = current.samples[OrientationSensor.MAGNETOMETER] ?: return
                if (abs(acceleration.timestamp - magnetic.timestamp) > MAX_PAIR_SKEW_NANOS ||
                    now - minOf(acceleration.timestamp, magnetic.timestamp) > STALE_NANOS
                ) return
                val matrix = platform.rotationMatrix(acceleration.values, magnetic.values) ?: return
                OrientationCandidate(
                    matrix = matrix,
                    timestamp = maxOf(acceleration.timestamp, magnetic.timestamp),
                    accelerationTimestamp = acceleration.timestamp,
                    magneticTimestamp = magnetic.timestamp,
                )
            }
            OrientationSource.ACCELEROMETER_ONLY -> OrientationCandidate(
                up = sample.values.copyOf(3), timestamp = timestamp, accelerationTimestamp = timestamp,
            )
            OrientationSource.UNAVAILABLE -> return
        }
        val angles = candidate.angles(rotation) ?: return
        current.latest = candidate
        // Sampling timestamps, rather than render time, define publication cadence.
        if (current.lastPublished == null || candidate.timestamp - current.lastPublished!! >= PUBLISH_NANOS ||
            mutableState.value.isStale || mutableState.value.angles == null
        ) publish(current, candidate, angles)
    }

    private fun publish(
        current: MonitoringSession,
        candidate: OrientationCandidate,
        angles: com.ambient.app.domain.orientation.OrientationAngles? = candidate.angles(rotation),
    ) {
        if (angles == null) return
        current.lastPublished = candidate.timestamp
        mutableState.value = OrientationReading(
            angles = angles,
            timestampNanos = candidate.timestamp,
            accuracy = accuracy(current),
            headingAccuracyDegrees = candidate.headingAccuracy,
            isListening = true,
            accelerationTimestampNanos = candidate.accelerationTimestamp,
            magneticTimestampNanos = candidate.magneticTimestamp,
            displayRotation = rotation,
        )
    }

    private fun updateAccuracy(current: MonitoringSession) {
        // Status callbacks never create a measurement or recover a stale/missing value.
        mutableState.value = mutableState.value.copy(accuracy = accuracy(current))
    }

    private fun accuracy(current: MonitoringSession): Int? {
        val reports = current.registered.map { current.accuracies[it] }
        return if (reports.any { it == null }) null else reports.filterNotNull().minOrNull()
    }

    private fun isFresh(candidate: OrientationCandidate, now: Long): Boolean =
        now >= candidate.timestamp && now - minOf(
            candidate.timestamp,
            candidate.accelerationTimestamp ?: candidate.timestamp,
            candidate.magneticTimestamp ?: candidate.timestamp,
        ) <= STALE_NANOS

    private fun scheduleStaleCheck(current: MonitoringSession) {
        current.subscription.postDelayed(STALE_CHECK_MILLIS) {
            synchronized(lock) {
                if (session === current) {
                    current.latest?.let { latest ->
                        if (!isFresh(latest, clockNanos())) {
                            mutableState.value = mutableState.value.copy(
                                angles = null,
                                isStale = true,
                                error = "姿态读数已超过 2 秒未更新，请稍候或重新开始探测",
                            )
                        }
                    }
                    scheduleStaleCheck(current)
                }
            }
        }
    }

    private class MonitoringSession(val startedAt: Long) {
        lateinit var subscription: OrientationSubscription
        val registered = mutableSetOf<OrientationSensor>()
        val samples = mutableMapOf<OrientationSensor, OrientationSample>()
        val accuracies = mutableMapOf<OrientationSensor, Int?>()
        var latest: OrientationCandidate? = null
        var lastPublished: Long? = null
    }

    private data class OrientationSample(val values: FloatArray, val timestamp: Long, val accuracy: Int?)
    private data class OrientationCandidate(
        val matrix: FloatArray? = null,
        val up: FloatArray? = null,
        val timestamp: Long,
        val headingAccuracy: Float? = null,
        val accelerationTimestamp: Long? = null,
        val magneticTimestamp: Long? = null,
    ) {
        fun angles(rotation: DisplayRotation) = matrix?.let { OrientationMath.fromRotationMatrix(it, rotation) }
            ?: up?.let { OrientationMath.fromUpVector(it, rotation) }
    }

    private companion object {
        const val FIRST_EVENT_TIMEOUT_MILLIS = 5_000L
        const val STALE_CHECK_MILLIS = 250L
        const val STALE_NANOS = 2_000_000_000L
        const val MAX_PAIR_SKEW_NANOS = 1_000_000_000L
        const val PUBLISH_NANOS = 100_000_000L
        const val MIN_GRAVITY = 0.980665
        fun validAccuracy(value: Int?): Int? = value?.takeIf { it in 0..3 }
    }
}

internal enum class OrientationSensor { ROTATION_VECTOR, ACCELEROMETER, MAGNETOMETER }

/** Small Android boundary used by deterministic acquisition/lifecycle tests. */
internal interface OrientationPlatform {
    val sensors: Map<OrientationSensor, String>
    fun open(
        onSample: (OrientationSensor, FloatArray, Long, Int?) -> Unit,
        onAccuracy: (OrientationSensor, Int?) -> Unit,
    ): OrientationSubscription
    fun rotationMatrix(rotationVector: FloatArray): FloatArray?
    fun rotationMatrix(acceleration: FloatArray, magnetic: FloatArray): FloatArray?
}

internal interface OrientationSubscription {
    fun register(kind: OrientationSensor): Boolean
    fun postDelayed(delayMillis: Long, action: () -> Unit)
    fun close()
}

internal class AndroidOrientationPlatform(context: Context) : OrientationPlatform {
    private val manager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val actualSensors = OrientationSensor.entries.mapNotNull { kind ->
        manager?.getDefaultSensor(kind.androidType)?.let { kind to it }
    }.toMap()
    override val sensors = actualSensors.mapValues { it.value.name }

    override fun open(
        onSample: (OrientationSensor, FloatArray, Long, Int?) -> Unit,
        onAccuracy: (OrientationSensor, Int?) -> Unit,
    ): OrientationSubscription {
        val thread = HandlerThread("AmbientOrientation").apply { start() }
        val handler = Handler(thread.looper)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val kind = OrientationSensor.entries.firstOrNull { it.androidType == event.sensor.type } ?: return
                onSample(kind, event.values.copyOf(), event.timestamp, event.accuracy)
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                val kind = OrientationSensor.entries.firstOrNull { it.androidType == sensor.type } ?: return
                onAccuracy(kind, accuracy)
            }
        }
        return object : OrientationSubscription {
            override fun register(kind: OrientationSensor): Boolean = actualSensors[kind]?.let {
                manager?.registerListener(listener, it, SAMPLE_PERIOD_MICROS, handler)
            } == true
            override fun postDelayed(delayMillis: Long, action: () -> Unit) { handler.postDelayed(action, delayMillis) }
            override fun close() {
                try { manager?.unregisterListener(listener) } finally {
                    handler.removeCallbacksAndMessages(null)
                    thread.quitSafely()
                }
            }
        }
    }

    override fun rotationMatrix(rotationVector: FloatArray): FloatArray? = FloatArray(9).also {
        SensorManager.getRotationMatrixFromVector(it, rotationVector)
    }.takeIf { matrix -> matrix.all(Float::isFinite) }

    override fun rotationMatrix(acceleration: FloatArray, magnetic: FloatArray): FloatArray? = FloatArray(9).let {
        if (SensorManager.getRotationMatrix(it, null, acceleration, magnetic) && it.all(Float::isFinite)) it else null
    }

    private companion object {
        const val SAMPLE_PERIOD_MICROS = 50_000
        val OrientationSensor.androidType: Int get() = when (this) {
            OrientationSensor.ROTATION_VECTOR -> Sensor.TYPE_ROTATION_VECTOR
            OrientationSensor.ACCELEROMETER -> Sensor.TYPE_ACCELEROMETER
            OrientationSensor.MAGNETOMETER -> Sensor.TYPE_MAGNETIC_FIELD
        }
    }
}
