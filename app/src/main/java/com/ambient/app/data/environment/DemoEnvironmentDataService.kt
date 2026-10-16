package com.ambient.app.data.environment

import com.ambient.app.domain.environment.DataSource
import com.ambient.app.domain.environment.EnvironmentDataService
import com.ambient.app.domain.environment.EnvironmentMetric
import com.ambient.app.domain.environment.EnvironmentSnapshot
import java.time.Clock

/** Fixed design fixtures, never randomized and never represented as a live observation. */
class DemoEnvironmentDataService(private val clock: Clock = Clock.systemUTC()) : EnvironmentDataService {
    override suspend fun fetch() = EnvironmentSnapshot(
        areaLabel = "示例区域 · 未定位",
        updatedAt = clock.instant(),
        metrics = listOf(
            metric("temperature", "室外温度", "24.6", "°C", "固定演示值；不是手机温度或实时天气。"),
            metric("humidity", "相对湿度", "62", "%", "固定演示值；表示空气中水汽相对饱和程度。"),
            metric("altitude", "地形海拔", "—", "m", "尚未接入独立海拔数据源；不将定位高度冒充地形海拔。"),
            metric("pressure", "区域气压", "1012.8", "hPa", "固定演示值；设备气压见手机实测页。"),
            metric("aqi", "空气质量", "42", "AQI", "固定演示值；未来接入时明确AQI采用的标准。"),
            metric("pm25", "PM2.5", "18", "μg/m³", "固定演示值；不代表所在地空气质量。"),
            metric("pm10", "PM10", "31", "μg/m³", "固定演示值；不代表所在地空气质量。"),
            metric("windSpeed", "风速", "2.4", "m/s", "固定演示值；区域风速不等于手机周围瞬时风速。"),
            metric("windDirection", "风向", "东北", "", "固定演示值；亮点位于东北，箭头从东北吹向中心。罗盘上北下南，不随手机转动。")
                .copy(windFromDegrees = 45.0),
            metric("uv", "紫外线指数", "3", "UVI", "固定演示值；没有用光照传感器估算紫外线。"),
        ),
    )

    private fun metric(id: String, label: String, value: String, unit: String, description: String) =
        EnvironmentMetric(id, label, value, unit, DataSource.DEMO, description)
}
