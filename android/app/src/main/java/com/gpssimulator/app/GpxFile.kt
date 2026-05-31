package com.gpssimulator.app

import org.json.JSONObject

data class GpxFile(
    val id: String,
    val name: String,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val pointCount: Int,
    val sizeBytes: Long,
    val hasTime: Boolean,
) {
    val distanceKm: Double get() = distanceMeters / 1000.0
    val durationFormatted: String
        get() {
            val total = durationSeconds
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s)
            else "%02d:%02d".format(m, s)
        }

    companion object {
        fun fromJson(o: JSONObject): GpxFile = GpxFile(
            id = o.getString("id"),
            name = o.getString("name"),
            distanceMeters = o.getDouble("distanceMeters"),
            durationSeconds = o.getLong("durationSeconds"),
            pointCount = o.getInt("pointCount"),
            sizeBytes = o.getLong("sizeBytes"),
            hasTime = o.optBoolean("hasTime", true),
        )
    }
}
