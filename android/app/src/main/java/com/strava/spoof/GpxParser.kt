package com.strava.spoof

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.format.DateTimeParseException

class GpxParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

object GpxParser {

    fun parse(input: InputStream): List<TrackPoint> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val points = mutableListOf<TrackPoint>()
        var inTrkpt = false
        var lat: Double? = null
        var lon: Double? = null
        var ele: Double? = null
        var time: Instant? = null
        var currentTag: String? = null

        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        if (parser.name == "trkpt") {
                            inTrkpt = true
                            ele = null
                            time = null
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inTrkpt && currentTag != null) {
                            val text = parser.text?.trim().orEmpty()
                            if (text.isNotEmpty()) {
                                when (currentTag) {
                                    "ele" -> ele = text.toDoubleOrNull()
                                    "time" -> time = parseTime(text)
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "trkpt") {
                            if (lat == null || lon == null) {
                                throw GpxParseException("trkpt missing lat/lon")
                            }
                            val t = time ?: throw GpxParseException(
                                "trkpt missing <time>. v1 requires timed GPX. " +
                                    "Run through gpx_add_time.py first."
                            )
                            points.add(TrackPoint(lat!!, lon!!, ele, t))
                            inTrkpt = false
                        }
                        currentTag = null
                    }
                }
                event = parser.next()
            }
        } catch (e: GpxParseException) {
            throw e
        } catch (e: IOException) {
            throw GpxParseException("Failed reading GPX stream", e)
        } catch (e: Exception) {
            throw GpxParseException("Malformed GPX: ${e.message}", e)
        }

        if (points.isEmpty()) {
            throw GpxParseException("GPX has no trackpoints")
        }
        if (points.size < 2) {
            throw GpxParseException("GPX needs at least two trackpoints")
        }
        return points
    }

    private fun parseTime(text: String): Instant {
        return try {
            Instant.parse(text)
        } catch (e: DateTimeParseException) {
            throw GpxParseException("Invalid ISO-8601 time: $text", e)
        }
    }
}
