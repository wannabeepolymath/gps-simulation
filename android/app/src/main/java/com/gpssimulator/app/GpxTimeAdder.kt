package com.gpssimulator.app

import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GpxTimeAdder {

    private val SELF_CLOSING_RE = Regex("""<trkpt\b([^>]*?)/>""")
    private val ATTR_RE = Regex("""(\w+)\s*=\s*"([^"]*)"""")
    private val TIME_TAG_RE = Regex("""<time>[^<]*</time>""")

    /** True if at least one <time> tag is present anywhere in the file. */
    fun hasAnyTimeTag(bytes: ByteArray): Boolean {
        return TIME_TAG_RE.containsMatchIn(bytes.toString(Charsets.UTF_8))
    }

    data class Result(val bytes: ByteArray, val pointCount: Int)

    /**
     * Rewrite the GPX so every trkpt has a <time>. Walks points in document
     * order, accumulating great-circle distance from the previous point, and
     * assigns timestamps from `start` using the given pace (sec/km).
     * Existing <time> tags inside trkpts are overwritten.
     *
     * Jitter: when `variabilityPercent > 0`, each per-segment time delta is
     * multiplied by `1 + N(0, sigma)` with `sigma = variability/100`, clamped
     * to [0.5x, 2.0x] so a segment never reverses time or collapses to zero.
     * The RNG is seeded from (start, pace, variability) so the same inputs
     * produce the same output. Pass 0 (default) for a perfectly flat pace.
     *
     * @param paceSecPerKm pace in seconds per kilometre (e.g. 5:30 → 330)
     * @param variabilityPercent 0..50; per-segment Gaussian sigma in percent.
     */
    fun addTimes(
        bytes: ByteArray,
        start: Instant,
        paceSecPerKm: Int,
        variabilityPercent: Int = 0,
    ): Result {
        require(paceSecPerKm > 0) { "pace must be positive" }
        require(variabilityPercent in 0..50) { "variability must be 0..50%" }
        val xml = bytes.toString(Charsets.UTF_8)
        val secPerMeter = paceSecPerKm / 1000.0
        val sigma = variabilityPercent / 100.0
        val rng = if (sigma > 0.0) java.util.Random(
            start.epochSecond * 1_000_003L +
                paceSecPerKm.toLong() * 31L +
                variabilityPercent.toLong()
        ) else null

        // First pass: find all trkpt occurrences (open-with-content + self-closing),
        // compute (lat, lon) and cumulative distance → assigned time.
        data class Hit(val start: Int, val end: Int, val attrs: String, val inner: String, val selfClosing: Boolean)
        val hits = mutableListOf<Hit>()
        // Open trkpt ... </trkpt>
        val openCloseRe = Regex("""<trkpt\b([^/>]*)>([\s\S]*?)</trkpt>""")
        for (m in openCloseRe.findAll(xml)) {
            hits += Hit(
                start = m.range.first,
                end = m.range.last + 1,
                attrs = m.groupValues[1],
                inner = m.groupValues[2],
                selfClosing = false,
            )
        }
        for (m in SELF_CLOSING_RE.findAll(xml)) {
            hits += Hit(
                start = m.range.first,
                end = m.range.last + 1,
                attrs = m.groupValues[1],
                inner = "",
                selfClosing = true,
            )
        }
        if (hits.isEmpty()) throw IllegalArgumentException("GPX has no trackpoints")
        hits.sortBy { it.start }

        var prevLat = Double.NaN
        var prevLon = Double.NaN
        var cumulativeSec = 0.0

        val sb = StringBuilder(xml.length + hits.size * 40)
        var cursor = 0

        for (h in hits) {
            val a = parseAttrs(h.attrs)
            val lat = a["lat"]?.toDoubleOrNull()
                ?: throw IllegalArgumentException("trkpt missing valid lat")
            val lon = a["lon"]?.toDoubleOrNull()
                ?: throw IllegalArgumentException("trkpt missing valid lon")

            val segmentM = if (!prevLat.isNaN()) {
                haversineMeters(prevLat, prevLon, lat, lon)
            } else 0.0
            val expectedDeltaSec = segmentM * secPerMeter
            val deltaSec = if (rng != null && expectedDeltaSec > 0.0) {
                val mul = (1.0 + rng.nextGaussian() * sigma).coerceIn(0.5, 2.0)
                expectedDeltaSec * mul
            } else expectedDeltaSec
            cumulativeSec += deltaSec
            prevLat = lat
            prevLon = lon

            val timestamp = start.plusMillis((cumulativeSec * 1000.0).toLong())
            val timeTag = "<time>${timestamp}</time>"

            sb.append(xml, cursor, h.start)
            if (h.selfClosing) {
                sb.append("<trkpt").append(h.attrs).append(">")
                sb.append(timeTag)
                sb.append("</trkpt>")
            } else {
                // Strip any existing <time> tags inside inner, then append a fresh one
                // right before </trkpt>.
                val cleanedInner = TIME_TAG_RE.replace(h.inner, "")
                sb.append("<trkpt").append(h.attrs).append(">")
                sb.append(cleanedInner)
                sb.append(timeTag)
                sb.append("</trkpt>")
            }
            cursor = h.end
        }
        sb.append(xml, cursor, xml.length)

        return Result(sb.toString().toByteArray(Charsets.UTF_8), hits.size)
    }

    private fun parseAttrs(raw: String): Map<String, String> {
        val out = HashMap<String, String>(4)
        for (m in ATTR_RE.findAll(raw)) out[m.groupValues[1]] = m.groupValues[2]
        return out
    }

    private const val EARTH_R = 6_371_000.0
    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2).let { it * it }
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_R * c
    }
}

/** Parse "MM:SS" → seconds per km. Throws on bad input. */
fun parsePaceMmSs(text: String): Int {
    val m = Regex("""^(\d+):([0-5]?\d)$""").matchEntire(text.trim())
        ?: throw IllegalArgumentException("Pace must be MM:SS (e.g. 5:30)")
    val mins = m.groupValues[1].toInt()
    val secs = m.groupValues[2].toInt()
    val total = mins * 60 + secs
    if (total <= 0) throw IllegalArgumentException("Pace must be positive")
    return total
}
