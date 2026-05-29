package com.strava.spoof

import android.content.Context
import android.net.Uri
import java.io.File

class GpxFileRepository(private val context: Context) {

    private val gpxDir: File
        get() = File(context.filesDir, "gpx").apply { if (!exists()) mkdirs() }

    fun list(): List<GpxFile> {
        return gpxDir.listFiles { f -> f.extension.equals("gpx", ignoreCase = true) }
            ?.mapNotNull { gpxFile ->
                val metaFile = File(gpxFile.parentFile, gpxFile.nameWithoutExtension + ".meta.json")
                val meta = if (metaFile.exists()) {
                    runCatching { GpxFileMeta.fromJson(metaFile.readText()) }.getOrNull()
                } else null
                val resolved = meta ?: rebuildMeta(gpxFile)
                resolved?.let { GpxFile(gpxFile.name, gpxFile.absolutePath, it) }
            }
            ?.sortedByDescending { File(it.path).lastModified() }
            ?: emptyList()
    }

    fun import(uri: Uri, displayName: String): GpxFile {
        val raw = context.contentResolver.openInputStream(uri)
            ?: throw GpxParseException("Could not open file")
        val bytes = raw.use { it.readBytes() }

        val points = bytes.inputStream().use { GpxParser.parse(it) }

        val safeBase = sanitize(displayName.removeSuffix(".gpx").removeSuffix(".GPX"))
        val target = File(gpxDir, uniqueName("$safeBase.gpx"))
        target.writeBytes(bytes)

        val meta = computeMeta(target.name, points)
        File(gpxDir, target.nameWithoutExtension + ".meta.json").writeText(meta.toJson())

        return GpxFile(target.name, target.absolutePath, meta)
    }

    fun delete(file: GpxFile) {
        File(file.path).delete()
        File(file.path.removeSuffix(".gpx") + ".meta.json").delete()
    }

    private fun rebuildMeta(gpxFile: File): GpxFileMeta? {
        return runCatching {
            val points = gpxFile.inputStream().use { GpxParser.parse(it) }
            computeMeta(gpxFile.name, points).also { meta ->
                File(gpxFile.parentFile, gpxFile.nameWithoutExtension + ".meta.json")
                    .writeText(meta.toJson())
            }
        }.getOrNull()
    }

    private fun computeMeta(name: String, points: List<TrackPoint>): GpxFileMeta {
        val distance = Geo.totalDistanceMeters(points)
        val duration = points.last().time.epochSecond - points.first().time.epochSecond
        return GpxFileMeta(
            name = name,
            pointCount = points.size,
            distanceMeters = distance,
            durationSeconds = duration.coerceAtLeast(0),
        )
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_')
        return cleaned.ifEmpty { "route" }
    }

    private fun uniqueName(base: String): String {
        if (!File(gpxDir, base).exists()) return base
        val stem = base.removeSuffix(".gpx")
        var i = 2
        while (File(gpxDir, "${stem}_$i.gpx").exists()) i++
        return "${stem}_$i.gpx"
    }
}
