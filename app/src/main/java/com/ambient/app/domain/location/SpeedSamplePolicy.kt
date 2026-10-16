package com.ambient.app.domain.location

/** Deliberately excludes coordinates: this feature neither stores nor infers a route. */
data class SpeedFix(
    val hasSpeed: Boolean,
    val metersPerSecond: Float,
    val accuracyMetersPerSecond: Float?,
    val elapsedRealtimeNanos: Long,
    val isMock: Boolean,
)

/** Uses the fix's monotonic timestamp, never UI render time or the wall clock. */
object SpeedSamplePolicy {
    const val MAX_AGE_NANOS = 10_000_000_000L

    fun evaluate(fix: SpeedFix, nowElapsedRealtimeNanos: Long): SpeedState {
        if (fix.elapsedRealtimeNanos <= 0L || nowElapsedRealtimeNanos <= 0L ||
            fix.elapsedRealtimeNanos > nowElapsedRealtimeNanos
        ) {
            return SpeedState(SpeedStatus.ERROR, message = "定位时间戳无效，等待新的定位结果")
        }
        if (nowElapsedRealtimeNanos - fix.elapsedRealtimeNanos > MAX_AGE_NANOS) {
            return SpeedState(SpeedStatus.STALE, message = "定位结果已超过 10 秒，等待新的速度数据")
        }
        if (!fix.hasSpeed) {
            return SpeedState(SpeedStatus.SPEED_UNAVAILABLE, message = "已收到定位，但系统尚未提供速度")
        }
        if (!fix.metersPerSecond.isFinite() || fix.metersPerSecond < 0f ||
            !(fix.metersPerSecond * 3.6f).isFinite()
        ) {
            return SpeedState(SpeedStatus.ERROR, message = "系统返回的速度无效，等待新的定位结果")
        }
        val accuracy = fix.accuracyMetersPerSecond?.takeIf { it.isFinite() && it >= 0f }
        return SpeedState(
            status = SpeedStatus.ACTIVE,
            sample = SpeedSample(fix.metersPerSecond, accuracy, fix.elapsedRealtimeNanos, fix.isMock),
        )
    }
}
