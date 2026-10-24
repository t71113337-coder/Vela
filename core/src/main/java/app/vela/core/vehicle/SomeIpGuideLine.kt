package app.vela.core.vehicle

import app.vela.core.model.LatLng
import app.vela.core.nav.RouteProjection
import java.util.Locale

/**
 * Builds the world-anchored extras of the rich windshield frame (SPEC 10.9) from Vela's own route and
 * progress: the route-snapped vehicle point, the route bearing there, the maneuver point, the guide
 * line ahead, and the route progress. A richer HUD renderer's AR layer reads these to place the arrow
 * on the road; a known-good third-party sender publishes the same set. Pure, so it is tested without a
 * device. The vehicle point comes from the distance traveled along the route, so no raw GPS fix is
 * needed here. Coordinates are longitude-first "lon,lat,0" and rounded to 1e-7, as the renderer reads
 * them; the number format is forced to US so a comma locale cannot corrupt the JSON.
 */
object SomeIpGuideLine {
    /** Guide line clipped to this far past the maneuver. */
    const val LINE_AHEAD_M = 120.0
    private const val MIN_POINTS = 3
    private const val MAX_POINTS = 256

    /** [carM] = meters traveled along [poly]; [maneuverM] = meters to the next maneuver from the
     *  route start; [routeLenM] = the route's full length. Returns extras with whatever could be
     *  derived; the guide line is null when it would be too short or the route too coarse. */
    fun extras(
        poly: List<LatLng>,
        carM: Double,
        maneuverM: Double,
        routeLenM: Double,
    ): Derived {
        if (poly.size < 2) return Derived()
        val cum = RouteProjection.cumulative(poly)
        val total = cum.last()
        val car = carM.coerceIn(0.0, total)
        val carPt = RouteProjection.pointAt(poly, cum, car)
        val heading = RouteProjection.bearingAt(poly, cum, car)
        val progress = if (routeLenM > 0.0) (carM / routeLenM).coerceIn(0.0, 1.0) else null
        val manPt = if (maneuverM in 0.0..total) RouteProjection.pointAt(poly, cum, maneuverM) else null
        val line = buildLine(poly, cum, total, car, maneuverM)
        return Derived(
            carLon = carPt.lng, carLat = carPt.lat,
            headingDeg = heading,
            progress = progress,
            maneuverPoint = manPt?.let { coord(it) },
            guideLineJson = line,
        )
    }

    data class Derived(
        val carLon: Double? = null,
        val carLat: Double? = null,
        val headingDeg: Double? = null,
        val progress: Double? = null,
        val maneuverPoint: String? = null,
        val guideLineJson: String? = null,
    )

    private fun buildLine(poly: List<LatLng>, cum: DoubleArray, total: Double, carM: Double, maneuverM: Double): String? {
        val endM = minOf(total, maneuverM + LINE_AHEAD_M)
        if (endM - carM < 1.0) return null
        val pts = ArrayList<LatLng>(16)
        pts.add(RouteProjection.pointAt(poly, cum, carM))
        var inserted = false
        for (i in poly.indices) {
            val c = cum[i]
            if (c <= carM) continue
            if (c >= endM) break
            if (!inserted && maneuverM in carM..endM && maneuverM < c) {
                pts.add(RouteProjection.pointAt(poly, cum, maneuverM)); inserted = true
            }
            pts.add(poly[i])
        }
        if (!inserted && maneuverM in carM..endM) pts.add(RouteProjection.pointAt(poly, cum, maneuverM))
        pts.add(RouteProjection.pointAt(poly, cum, endM))
        val dedup = dedupe(pts)
        val sampled = if (dedup.size > MAX_POINTS) sample(dedup, MAX_POINTS) else dedup
        if (sampled.size < MIN_POINTS) return null
        val sb = StringBuilder(sampled.size * 24)
        sb.append('[')
        for ((i, p) in sampled.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append('[').append(num(p.lng)).append(',').append(num(p.lat)).append(",0]")
        }
        sb.append(']')
        return sb.toString()
    }

    private fun coord(p: LatLng): String = "${num(p.lng)},${num(p.lat)},0"

    private fun dedupe(pts: List<LatLng>): List<LatLng> {
        val out = ArrayList<LatLng>(pts.size)
        for (p in pts) {
            val last = out.lastOrNull()
            if (last == null || Math.abs(last.lat - p.lat) > 1e-7 || Math.abs(last.lng - p.lng) > 1e-7) out.add(p)
        }
        return out
    }

    // Keep the ends and an even spread of the interior, so a long route's line stays within the cap.
    private fun sample(pts: List<LatLng>, max: Int): List<LatLng> {
        val out = ArrayList<LatLng>(max)
        val step = (pts.size - 1).toDouble() / (max - 1)
        var i = 0
        while (i < max) { out.add(pts[Math.round(i * step).toInt().coerceAtMost(pts.size - 1)]); i++ }
        return dedupe(out)
    }

    // Round to 1e-7 and print without scientific notation or a trailing-zero tail. US locale only.
    private fun num(v: Double): String {
        val r = Math.round(v * 1e7) / 1e7
        var s = String.format(Locale.US, "%.7f", r)
        if (s.contains('.')) { s = s.trimEnd('0'); if (s.endsWith('.')) s += "0" }
        return s
    }
}
