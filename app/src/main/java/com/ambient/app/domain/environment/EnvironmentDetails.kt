package com.ambient.app.domain.environment

/** Detail-only fields share the weather snapshot, timestamps, persistence and provider boundary. */
object EnvironmentDetails {
    data class Definition(val id: String, val label: String, val unit: String, val meaning: String)
    val definitions = listOf(
        Definition("feelsLike", "体感温度", "°C", "提供方综合气象条件计算的体感参考，不是温度计实测，也不代表每个人的实际感受。"),
        Definition("dewPoint", "露点温度", "°C", "空气在水汽含量和气压基本不变时，冷却到水汽饱和的温度。可帮助理解空气水汽状况；不能单凭露点判断会下雨或室内物体会结露。"),
        Definition("visibility", "能见度", "m", "区域大气的水平能见距离参考，不是手机相机测距，也不保证你所在道路的实际视野。"),
        Definition("cloudCover", "云量", "%", "云覆盖天空的比例。不是地面光照强度，不能直接换算成手机光照读数或紫外线指数。"),
        Definition("windGust", "阵风风速", "m/s", "短时间内增强的风速，与通常风速不同。来自区域天气服务，不代表手机周围的瞬时风速；接口未说明统计时间窗，不标作当日最大风速。"),
    )
    val ids = definitions.map { it.id }.toSet()

    /** Older caches/records and demo mode never receive invented or back-calculated values. */
    fun metrics(snapshot: EnvironmentSnapshot?): List<EnvironmentMetric> = definitions.map { definition ->
        snapshot?.metrics?.firstOrNull { it.id == definition.id } ?: EnvironmentMetric(
            definition.id, definition.label, "—", definition.unit,
            if (snapshot?.isDemo == true) DataSource.DEMO else DataSource.REGIONAL_API, definition.meaning)
    }
    fun overviewMetrics(metrics: List<EnvironmentMetric>) = metrics.filterNot { it.id in ids }
}
