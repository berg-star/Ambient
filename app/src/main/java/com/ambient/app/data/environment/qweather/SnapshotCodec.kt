package com.ambient.app.data.environment.qweather

import com.ambient.app.domain.environment.EnvironmentMetric
import com.ambient.app.domain.environment.EnvironmentSnapshot
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant

/** Explicit Instant encoding avoids reflection into platform java.time internals. */
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
        return EnvironmentSnapshot(o.string("areaLabel"), Instant.parse(o.string("updatedAt")),
            o.array("metrics").map { gson.fromJson(it, EnvironmentMetric::class.java) },
            o.string("observedAt").takeIf { it.isNotBlank() }?.let(Instant::parse),
            o.array("attributions").map { it.asString }, o.array("notices").map { it.asString },
            o.get("isDemo").asBoolean)
    }
}
