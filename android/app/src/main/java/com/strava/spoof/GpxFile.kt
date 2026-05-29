package com.strava.spoof

import org.json.JSONObject

data class GpxFileMeta(
    val name: String,
    val pointCount: Int,
    val distanceMeters: Double,
    val durationSeconds: Long,
) {
    fun toJson(): String = JSONObject().apply {
        put("name", name)
        put("pointCount", pointCount)
        put("distanceMeters", distanceMeters)
        put("durationSeconds", durationSeconds)
    }.toString()

    companion object {
        fun fromJson(s: String): GpxFileMeta {
            val o = JSONObject(s)
            return GpxFileMeta(
                name = o.getString("name"),
                pointCount = o.getInt("pointCount"),
                distanceMeters = o.getDouble("distanceMeters"),
                durationSeconds = o.getLong("durationSeconds"),
            )
        }
    }
}

data class GpxFile(
    val name: String,
    val path: String,
    val meta: GpxFileMeta,
) {
    val distanceKm: Double get() = meta.distanceMeters / 1000.0
    val durationFormatted: String
        get() {
            val total = meta.durationSeconds
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s)
            else "%02d:%02d".format(m, s)
        }
}
