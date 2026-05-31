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
     * Rewrite the GPX so every trkpt has a <time>. Existing <time> tags
     * inside trkpts are overwritten.
     *
     * Pace model (when variabilityPercent > 0):
     *   per-segment time delta = baseline × warmup(t) × drift(progress) × (1 + noise)
     * where
     *   - baseline = segment_meters × seconds_per_meter
     *   - warmup(t): smooth ramp from +25% slower at t=0 to 0 by t=60s.
     *   - drift(progress): linear ±drift_total/2 across the run, drift_total
     *     sampled uniformly in [-3%, +3%] (deterministic from seed). Mimics
     *     positive/negative-split tendencies.
     *   - noise: AR(1) Gaussian with persistence alpha=0.85 and stdev sigma.
     *     This makes pace stay *near* recent pace (streaks) rather than
     *     zig-zagging segment-to-segment. Stationary stdev = sigma.
     * The final multiplier is clamped to [0.5x, 2.0x] so deltas stay positive
     * and bounded. RNG is seeded from (start, pace, variability) so identical
     * inputs produce identical files.
     *
     * Pass 0 for variabilityPercent to disable the entire model (flat pace,
     * matches the Python tools/gpx_add_time.py).
     *
     * @param paceSecPerKm pace in seconds per kilometre (e.g. 5:30 → 330)
     * @param variabilityPercent 0..50; AR(1) Gaussian sigma in percent.
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

        // Pre-compute segment distances so we know total expected duration up
        // front (needed to normalize warm-up and drift on a fixed timeline).
        val segmentM = DoubleArray(hits.size)
        run {
            var prevLat = Double.NaN
            var prevLon = Double.NaN
            for (i in hits.indices) {
                val a = parseAttrs(hits[i].attrs)
                val lat = a["lat"]?.toDoubleOrNull()
                    ?: throw IllegalArgumentException("trkpt missing valid lat")
                val lon = a["lon"]?.toDoubleOrNull()
                    ?: throw IllegalArgumentException("trkpt missing valid lon")
                segmentM[i] = if (!prevLat.isNaN()) haversineMeters(prevLat, prevLon, lat, lon) else 0.0
                prevLat = lat
                prevLon = lon
            }
        }
        val totalExpectedSec = segmentM.sum() * secPerMeter

        // AR(1) persistence — higher = streakier pace.
        val alpha = 0.85
        val arNoiseScale = sqrt(1.0 - alpha * alpha)
        // Warm-up: start 25% slower, ease to 1.0 by 60s of expected time.
        val warmupSlowdown = 0.25
        val warmupSec = 60.0
        // Long-term drift: uniform in [-3%, +3%] across the whole run.
        val driftTotal = rng?.let { it.nextDouble() * 0.06 - 0.03 } ?: 0.0

        var prevNoise = 0.0
        var cumulativeSec = 0.0
        var cumulativeExpectedSec = 0.0

        val sb = StringBuilder(xml.length + hits.size * 40)
        var cursor = 0

        for (i in hits.indices) {
            val h = hits[i]
            val expectedDeltaSec = segmentM[i] * secPerMeter
            val deltaSec = if (rng != null && expectedDeltaSec > 0.0) {
                // AR(1) noise: noise stays near recent noise → pace streaks.
                val innovation = rng.nextGaussian() * sigma * arNoiseScale
                val noise = alpha * prevNoise + innovation
                prevNoise = noise

                // Warm-up multiplier: smoothstep from (1 + slowdown) → 1.0
                val warmupProgress = (cumulativeExpectedSec / warmupSec).coerceIn(0.0, 1.0)
                val warmupEase = warmupProgress * warmupProgress * (3.0 - 2.0 * warmupProgress) // smoothstep
                val warmupMul = 1.0 + warmupSlowdown * (1.0 - warmupEase)

                // Drift: linear -drift/2 → +drift/2 across the run.
                val runProgress = if (totalExpectedSec > 0.0)
                    (cumulativeExpectedSec / totalExpectedSec).coerceIn(0.0, 1.0)
                else 0.0
                val driftMul = 1.0 + driftTotal * (runProgress - 0.5)

                val mul = (warmupMul * driftMul * (1.0 + noise)).coerceIn(0.5, 2.0)
                expectedDeltaSec * mul
            } else expectedDeltaSec

            cumulativeSec += deltaSec
            cumulativeExpectedSec += expectedDeltaSec

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

/**
 * Sensible jitter (%) for a given pace. Faster paces = tighter variance
 * (race discipline), slower paces = larger variance (recovery / walks /
 * mixed efforts). Picked to look organic on Strava's pace chart.
 */
fun defaultJitterForPace(paceSecPerKm: Int): Int = when {
    paceSecPerKm <= 240 -> 5    // ≤ 4:00/km — race / hard run
    paceSecPerKm <= 300 -> 7    // ≤ 5:00/km — steady
    paceSecPerKm <= 390 -> 10   // ≤ 6:30/km — easy run
    paceSecPerKm <= 540 -> 12   // ≤ 9:00/km — jog / brisk walk
    else -> 15                  // > 9:00/km — walk
}
