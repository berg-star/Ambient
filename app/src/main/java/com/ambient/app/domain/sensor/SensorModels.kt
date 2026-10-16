package com.ambient.app.domain.sensor

import kotlinx.coroutines.flow.StateFlow

enum class SensorKind(
    val displayName: String,
    val unit: String,
    val description: String,
) {
    LIGHT("环境光照", "lx", "手机光照传感器接收到的照度。遮挡、屏幕反光和设备摆放都会影响读数，不能用于专业照度校准。"),
    MAGNETIC_FIELD("地磁场", "μT", "磁场传感器在手机 X、Y、Z 三轴上的磁感应强度，以及三轴合成值。磁铁、金属和电器会干扰结果；这里的模长并非手机朝向。"),
    ACCELEROMETER("加速度", "m/s²", "加速度计的三轴读数和合成值，包含重力分量。手机静置时合成值通常接近当地重力加速度，并不代表手机正在移动。"),
    LINEAR_ACCELERATION("加速度", "m/s²", "由 Android 系统处理、去除重力后的三轴线性加速度。静置时通常接近 0，但会有噪声、零点偏差和处理延迟；转动手机时可能产生短暂误差。它不是速度，也不能用来可靠地推算移动距离。"),
    GYROSCOPE("陀螺仪", "rad/s", "手机绕 X、Y、Z 三轴旋转的角速度，以及三轴合成值。它表示旋转速度，并不是倾斜角度或朝向。"),
    PRESSURE("本机气压", "hPa", "手机气压传感器所在位置的大气压，可能受室内外环境和设备校准影响。此读数不等于气象站的海平面气压，也不会直接转换为地形海拔。");

    val isAcceleration: Boolean
        get() = this == ACCELEROMETER || this == LINEAR_ACCELERATION
}

enum class AccelerationMode(val kind: SensorKind, val label: String) {
    LINEAR(SensorKind.LINEAR_ACCELERATION, "去重力"),
    RAW(SensorKind.ACCELEROMETER, "含重力"),
}

data class SensorSample(val timestampNanos: Long, val value: Float)

data class SensorReading(
    val values: List<Float> = emptyList(),
    val magnitude: Float? = null,
    val timestampNanos: Long? = null,
    val accuracy: Int? = null,
    val samples: List<SensorSample> = emptyList(),
    val isListening: Boolean = false,
    val error: String? = null,
    val eventAccuracy: Int? = null,
    val callbackAccuracy: Int? = null,
)

data class SensorCapability(
    val available: Boolean,
    val name: String? = null,
    val vendor: String? = null,
    val resolution: Float? = null,
    val maximumRange: Float? = null,
)

data class SensorFeed(
    val kind: SensorKind,
    val capability: SensorCapability,
    val state: StateFlow<SensorReading>,
)

interface SensorRepository {
    val feeds: List<SensorFeed>
    fun setAccelerationMode(mode: AccelerationMode)
    fun start()
    fun stop()
}
