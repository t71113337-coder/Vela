package app.vela.core.vehicle

import app.vela.core.model.LatLng
import app.vela.core.nav.RouteProjection
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Builds the world-anchored extras of the rich windshield frame (SPEC 10.9) from Vela's own route and
 * progress: the route-snapped vehicle point, the route bearing there, the maneuver point, the guide
 * line ahead, and the route progress. A richer HUD renderer's AR layer reads these to place the arrow
 * on the road; a known-good third-party sender publishes the same set. Pure, so it is tested without a
 * device. The vehicle point comes from the distance traveled along the route, so no raw GPS fix is
 * needed here. Caller supplies the polyline that `traveledM` is measured along (the route's `polyline`,
 * NOT a display-only `drawPolyline`), and may cache the cumulative-distance array per route so this is
 * not O(route) per tick. Coordinates are longitude-first "lon,lat,0" and rounded to 1e-7; the number
 * format is forced to US so a comma locale cannot corrupt the JSON.
 */
object SomeIpGuideLine {
    /** Guide line clipped to this far past the maneuver (matches the reference sender). */
    const val LINE_AHEAD_M = 120.0
    private const val MIN_POINTS = 3
    private const val MAX_POINTS = 256

    data class Derived(
        val carLon: Double? = null,
        val carLat: Double? = null,
        val headingDeg: Double? = null,
        val progress: Double? = null,
        val maneuverPoint: String? = null,
        val guideLineJson: String? = null,
    )

    /** Convenience: computes the cumulative array itself. Prefer the [cum] overload in a per-tick loop. */
    fun extras(poly: List<LatLng>, carM: Double, maneuverM: Double, routeLenM: Double): Derived =
        if (poly.size < 2) Derived() else extras(poly, RouteProjection.cumulative(poly), carM, maneuverM, routeLenM)

    /** [carM] = meters traveled along [poly]; [maneuverM] = meters to the next maneuver from the route
     *  start; [routeLenM] = the route's full length; [cum] = `RouteProjection.cumulative(poly)`, which
     *  the caller should cache per route. The guide line is null when it would be too short. */
    fun extras(poly: List<LatLng>, cum: DoubleArray, carM: Double, maneuverM: Double, routeLenM: Double): Derived {
        if (poly.size < 2 || cum.size != poly.size) return Derived()
        val total = cum.last()
        val car = carM.coerceIn(0.0, total)
        val carPt = RouteProjection.pointAt(poly, cum, car)
        val heading = RouteProjection.bearingAt(poly, cum, car)
        val progress = if (routeLenM > 0.0) (carM / routeLenM).coerceIn(0.0, 1.0) else null
        val manPt = if (maneuverM in 0.0..total) RouteProjection.pointAt(poly, cum, maneuverM) else null
        return Derived(
            carLon = carPt.lng, carLat = carPt.lat,
            headingDeg = heading,
            progress = progress,
            maneuverPoint = manPt?.let { coord(it) },
            guideLineJson = buildLine(poly, cum, total, car, maneuverM, manPt),
        )
    }

    private fun buildLine(poly: List<LatLng>, cum: DoubleArray, total: Double, carM: Double, maneuverM: Double, manPt: LatLng?): String? {
        val endM = minOf(total, maneuverM + LINE_AHEAD_M)
        if (endM - carM < 1.0) return null
        val pts = ArrayList<LatLng>(32)
        pts.add(RouteProjection.pointAt(poly, cum, carM))
        val manInRange = manPt != null && maneuverM in carM..endM
        var inserted = false
        // Start at the first vertex past the car, not index 0, so this stays O(points on the line).
        var i = RouteProjection.segmentEnd(cum, carM)
        while (i < poly.size) {
            val c = cum[i]
            if (c <= carM) { i++; continue }
            if (c >= endM) break
            if (manInRange && !inserted && maneuverM < c) { pts.add(manPt!!); inserted = true }
            pts.add(poly[i]); i++
        }
        if (manInRange && !inserted) { pts.add(manPt!!); inserted = true }
        pts.add(RouteProjection.pointAt(poly, cum, endM))
        var line = dedupe(pts)
        if (line.size > MAX_POINTS) {
            // Always keep the ends and the maneuver vertex, so a long stretch does not sample it out.
            val manIdx = if (manPt != null) line.indexOfFirst { near(it, manPt) } else -1
            line = sample(line, MAX_POINTS, setOfNotNull(0, line.lastIndex, manIdx.takeIf { it >= 0 }))
        }
        if (line.size == 2) line = listOf(line[0], mid(line[0], line[1]), line[1]) // keep a straight 2-point line
        if (line.size < MIN_POINTS) return null
        val sb = StringBuilder(line.size * 24)
        sb.append('[')
        for ((j, p) in line.withIndex()) {
            if (j > 0) sb.append(',')
            sb.append('[').append(num(p.lng)).append(',').append(num(p.lat)).append(",0]")
        }
        sb.append(']')
        return sb.toString()
    }

    private fun coord(p: LatLng): String = "${num(p.lng)},${num(p.lat)},0"
    private fun mid(a: LatLng, b: LatLng) = LatLng((a.lat + b.lat) / 2, (a.lng + b.lng) / 2)
    private fun near(a: LatLng, b: LatLng) = Math.abs(a.lat - b.lat) <= 1e-7 && Math.abs(a.lng - b.lng) <= 1e-7

    private fun dedupe(pts: List<LatLng>): List<LatLng> {
        val out = ArrayList<LatLng>(pts.size)
        for (p in pts) { val last = out.lastOrNull(); if (last == null || !near(last, p)) out.add(p) }
        return out
    }

    // Even spread across the line, plus the forced [must] indices (the ends and the maneuver vertex).
    private fun sample(pts: List<LatLng>, max: Int, must: Set<Int>): List<LatLng> {
        if (pts.size <= max) return pts
        val keep = sortedSetOf<Int>().apply { addAll(must); add(0); add(pts.size - 1) }
        val slots = max - keep.size
        if (slots > 0) {
            val step = (pts.size - 1).toDouble() / (slots + 1)
            for (k in 1..slots) keep.add((k * step).roundToInt().coerceIn(0, pts.size - 1))
        }
        return keep.map { pts[it] }
    }

    // Round to 1e-7 and print without scientific notation or a trailing-zero tail. US locale only.
    private fun num(v: Double): String {
        val r = Math.round(v * 1e7) / 1e7
        var s = String.format(Locale.US, "%.7f", r)
        if (s.contains('.')) { s = s.trimEnd('0'); if (s.endsWith('.')) s += "0" }
        return s
    }
}
