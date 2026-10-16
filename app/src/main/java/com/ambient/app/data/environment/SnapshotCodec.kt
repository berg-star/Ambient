package com.ambient.app.data.environment

import com.ambient.app.domain.environment.EnvironmentMetric
import com.ambient.app.domain.environment.EnvironmentSnapshot
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant

/** Provider-independent snapshot payload, shared by the weather cache and immutable history. */
object SnapshotCodec {
    private val gson = Gson()
    fun encode(snapshot: EnvironmentSnapshot): String = JsonObject().apply {
        addProperty("areaLabel", snapshot.areaLabel)
        addProperty("updatedAt", snapshot.updatedAt.toString())
        addProperty("observedAt", snapshot.observedAt?.toString())
        addProperty("isDemo", snapshot.isDemo)
        add("metrics", gson.toJsonTree(snapshot.metrics))
        add("attributions", gson.toJsonTree(snapshot.attributions))
        add("notices", gson.toJsonTree(snapshot.notices))
    }.toString()

    fun decode(json: String): EnvironmentSnapshot {
        val o = JsonParser.parseString(json).asJsonObject
        fun text(key: String) = o.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        fun array(key: String) = o.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
        return EnvironmentSnapshot(text("areaLabel"), Instant.parse(text("updatedAt")),
            array("metrics").map { gson.fromJson(it, EnvironmentMetric::class.java) },
            text("observedAt").takeIf { it.isNotBlank() }?.let(Instant::parse),
            array("attributions").map { it.asString }, array("notices").map { it.asString }, o.get("isDemo").asBoolean)
    }
}
