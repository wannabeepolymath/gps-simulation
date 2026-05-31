package com.gpssimulator.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos

/**
 * Plots a list of lat/lon points as a polyline. Pure local rendering — no
 * map tiles, no network, no API key. Uses an equirectangular projection
 * with cos(midLat) correction so the path doesn't look stretched east-west
 * at non-equatorial latitudes. The whole route is auto-fit to the canvas
 * preserving aspect ratio.
 */
@Composable
fun RouteMap(
    points: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier,
) {
    val bgColor = MaterialTheme.colorScheme.surfaceVariant
    val lineColor = MaterialTheme.colorScheme.primary
    val startColor = Color(0xFF2E7D32)
    val endColor = Color(0xFFC62828)

    if (points.size < 2) {
        Box(modifier = modifier.background(bgColor))
        return
    }

    var minLat = Double.POSITIVE_INFINITY
    var maxLat = Double.NEGATIVE_INFINITY
    var minLon = Double.POSITIVE_INFINITY
    var maxLon = Double.NEGATIVE_INFINITY
    for ((lat, lon) in points) {
        if (lat < minLat) minLat = lat
        if (lat > maxLat) maxLat = lat
        if (lon < minLon) minLon = lon
        if (lon > maxLon) maxLon = lon
    }
    val midLat = (minLat + maxLat) / 2.0
    val cosMid = cos(Math.toRadians(midLat))
    val degW = ((maxLon - minLon) * cosMid).coerceAtLeast(1e-9)
    val degH = (maxLat - minLat).coerceAtLeast(1e-9)

    Canvas(modifier = modifier.background(bgColor)) {
        val pad = 12.dp.toPx()
        val w = (size.width - 2 * pad).coerceAtLeast(1f)
        val h = (size.height - 2 * pad).coerceAtLeast(1f)
        val scale = minOf(w / degW.toFloat(), h / degH.toFloat())
        val offX = pad + (w - degW.toFloat() * scale) / 2f
        val offY = pad + (h - degH.toFloat() * scale) / 2f

        fun project(lat: Double, lon: Double): Offset {
            val px = ((lon - minLon) * cosMid).toFloat() * scale + offX
            val py = (maxLat - lat).toFloat() * scale + offY
            return Offset(px, py)
        }

        val path = Path()
        val first = project(points[0].first, points[0].second)
        path.moveTo(first.x, first.y)
        var last = first
        for (i in 1 until points.size) {
            val (lat, lon) = points[i]
            val p = project(lat, lon)
            path.lineTo(p.x, p.y)
            last = p
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(
                width = 3.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
        drawCircle(color = startColor, radius = 5.dp.toPx(), center = first)
        drawCircle(color = endColor, radius = 5.dp.toPx(), center = last)
    }
}
