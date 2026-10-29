package com.ambient.app.domain.environment

import java.time.Instant
import java.util.Locale

/** Display and snapshot projection; never overwrites the provider's weather cache or retrieval time. */
fun positioningHeightMetric(area: EnvironmentArea?): EnvironmentMetric {
    val height = area?.positioningHeightMeters?.takeIf {
        area.fromDevice && it.isFinite() && it in -500.0..10000.0
    }
    val description = if (height == null) {
        "尚未取得有效定位高度。请点击“使用当前位置”并授予定位权限；室内或定位不含高度时显示横线。手选城市不使用手机的旧高度，0 米参考假设不会显示为读数。"
    } else buildString {
        val location = requireNotNull(area)
        if (location.isMock) append("模拟定位 · 测试值，非真实手机高度。")
        append("上次单次定位报告的手机近似高度（WGS84 椭球高），不是地形或平均海平面海拔；显示值按米取整。")
        val accuracy = location.verticalAccuracyMeters?.takeIf { it.isFinite() && it >= 0.0 }
        append(if (accuracy != null) "系统报告的垂直精度 ±${String.format(Locale.US, "%.0f", accuracy)} m。" else "系统未提供垂直精度。")
        append(location.positioningHeightAtMillis?.takeIf { it > 0 }?.let {
            "定位时间（UTC）：${Instant.ofEpochMilli(it)}。"
        } ?: "该次定位时间未记录。")
        append("天气刷新不会更新这个高度；移动后请重新点击“使用当前位置”。")
    }
    return EnvironmentMetric("altitude", "近似定位高度", height?.let { String.format(Locale.US, "%.0f", it) } ?: "—",
        "m", DataSource.POSITIONING, description, isSimulated = height != null && area?.isMock == true,
        observedAtMillis = area?.positioningHeightAtMillis?.takeIf { height != null && it > 0 })
}

fun EnvironmentSnapshot.withPositioningHeight(area: EnvironmentArea?): EnvironmentSnapshot {
    if (isDemo) return this
    // Protect against a previous city's weather while a new location is being selected.
    val metric = positioningHeightMetric(area?.takeIf { it.label == areaLabel })
    return copy(metrics = metrics.filterNot { it.id == "altitude" } + metric)
}
