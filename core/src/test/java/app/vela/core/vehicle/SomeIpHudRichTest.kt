package app.vela.core.vehicle

import app.vela.core.model.LatLng
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rich windshield frame (SPEC 10.9) and the guide-line builder. The rich frame mirrors a
 * known-good third-party sender's field set; the expected bytes/values here are hand-derived from the
 * documented contract, not produced by calling the builder. The rich path is car-unverified and runs
 * only behind the `hudRich` dev dial, so these tests guard the wire shape, not an on-car result.
 */
class SomeIpHudRichTest {
    // Decode outer field 1 into (fieldNumber, wireType, raw) in wire order.
    private data class F(val num: Int, val wire: Int, val v: Long, val bytes: ByteArray?)

    private fun inner(frame: ByteArray): List<F> {
        var i = 0
        fun varint(): Long { var s = 0; var r = 0L; while (true) { val b = frame[i++].toInt() and 0xff; r = r or ((b and 0x7f).toLong() shl s); if (b < 0x80) return r; s += 7 } }
        assertEquals(0x0AL, varint())
        val len = varint().toInt(); val end = i + len
        val out = ArrayList<F>()
        while (i < end) {
            val tag = varint(); val num = (tag shr 3).toInt(); val wire = (tag and 7).toInt()
            when (wire) {
                0 -> out.add(F(num, 0, varint(), null))
                1 -> { var bits = 0L; for (k in 0 until 8) bits = bits or ((frame[i++].toLong() and 0xff) shl (8 * k)); out.add(F(num, 1, bits, null)) }
                2 -> { val l = varint().toInt(); val b = frame.copyOfRange(i, i + l); i += l; out.add(F(num, 2, 0, b)) }
                else -> error("wire $wire")
            }
        }
        return out
    }

    private fun str(f: F) = String(f.bytes!!, Charsets.UTF_8)
    private fun dbl(f: F) = java.lang.Double.longBitsToDouble(f.v)

    @Test fun `rich frame without AR fields is byte-for-byte the contract bytes`() {
        val extras = RoadExtras(remainingMeters = 1500, remainingSeconds = 600, remainingTimeText = "10 min")
        val frame = SomeIpHudFrame.guidanceRich(1, 300, "Main", "14:05", byteArrayOf(0x11, 0x22, 0x33), extras)
        val expected = byteArrayOf(
            0x0A, 0x2F,
            0x10, 0x02,                                     // f2 = 2
            0x80.toByte(), 0x01, 0x02,                      // f16 = 2
            0x28, 0x00,                                     // f5 = 0
            0x18, 0xDC.toByte(), 0x0B,                      // f3 = 1500
            0x20, 0xD8.toByte(), 0x04,                      // f4 = 600
            0x42, 0x03, 0x11, 0x22, 0x33,                   // f8 png
            0x48, 0xAC.toByte(), 0x02,                      // f9 = 300 (no 11 m floor)
            0x52, 0x04, 0x4D, 0x61, 0x69, 0x6E,             // f10 = "Main"
            0xD2.toByte(), 0x01, 0x05, 0x31, 0x34, 0x3A, 0x30, 0x35, // f26 = "14:05"
            0xDA.toByte(), 0x01, 0x06, 0x31, 0x30, 0x20, 0x6D, 0x69, 0x6E, // f27 = "10 min"
            0xE0.toByte(), 0x01, 0x01,                      // f28 = 1
        )
        assertArrayEquals(expected, frame)
    }

    @Test fun `rich frame carries the AR fields as the right wire types and values`() {
        val extras = RoadExtras(
            remainingMeters = 800, remainingSeconds = 120, remainingTimeText = "2 min",
            carLon = 37.6173, carLat = 55.7558, headingDeg = 90.0, progress = 0.5,
            guideLineJson = "[[37.6173,55.7558,0]]", maneuverPoint = "37.62,55.76,0", laneCount = 2,
        )
        val f = inner(SomeIpHudFrame.guidanceRich(2, 50, "Road", "09:00", null, extras))
        assertEquals(listOf(2, 16, 5, 3, 4, 8, 9, 10, 26, 27, 28, 19, 20, 30, 31, 32, 33), f.map { it.num })
        assertEquals(2L, f.first { it.num == 5 }.v)                 // lane count
        assertEquals(37.6173, dbl(f.first { it.num == 19 }), 1e-12) // car lon
        assertEquals(55.7558, dbl(f.first { it.num == 20 }), 1e-12) // car lat
        assertEquals(90.0, dbl(f.first { it.num == 32 }), 1e-12)    // heading
        assertEquals(0.5, dbl(f.first { it.num == 33 }), 1e-12)     // progress
        assertEquals("[[37.6173,55.7558,0]]", str(f.first { it.num == 30 }))
        assertEquals("37.62,55.76,0", str(f.first { it.num == 31 }))
        assertEquals(1, f.count { it.num == 19 })                   // fixed64 is 8 bytes, one field
    }

    @Test fun `absent AR values are omitted, not zeroed`() {
        val f = inner(SomeIpHudFrame.guidanceRich(1, 50, "R", "", null, RoadExtras()))
        val nums = f.map { it.num }
        for (n in listOf(3, 4, 19, 20, 27, 30, 31, 32, 33)) assertTrue("f$n must be omitted", n !in nums)
        assertTrue(0 == f.first { it.num == 5 }.v.toInt())          // lane count still written as 0
        assertTrue(26 !in nums)                                     // empty ETA omitted
    }

    @Test fun `rich clear is 2-16-5, no field 6`() {
        val f = inner(SomeIpHudFrame.clearRich())
        assertEquals(listOf(2, 16, 5), f.map { it.num })
        assertEquals(1L, f.first { it.num == 16 }.v)
        assertEquals(0L, f.first { it.num == 5 }.v)
    }

    @Test fun `map window wraps the base64 as outer field 1`() {
        val frame = SomeIpHudFrame.mapWindow("QUJD")  // Base64("ABC")
        assertArrayEquals(byteArrayOf(0x0A, 0x04, 0x51, 0x55, 0x4A, 0x44), frame)
    }

    @Test fun `guide line clips from the car to past the maneuver and snaps the car point`() {
        // A due-east straight line at lat 0, ~1 m per 1e-5 deg lon near the equator is not exact, so
        // use coarse points and assert structure, progress and the car/maneuver points.
        val poly = listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.01), LatLng(0.0, 0.02), LatLng(0.0, 0.03))
        val total = app.vela.core.nav.RouteProjection.cumulative(poly).last()
        val carM = total * 0.25
        val maneuverM = total * 0.5
        val d = SomeIpGuideLine.extras(poly, carM, maneuverM, total)
        assertEquals(0.25, d.progress!!, 1e-9)
        assertEquals(0.0, d.carLat!!, 1e-9)
        assertTrue(d.carLon!! in 0.0074..0.0076)                   // a quarter along, snapped to the line
        assertTrue(d.guideLineJson!!.startsWith("[[") && d.guideLineJson!!.endsWith("]]"))
        assertTrue(d.guideLineJson!!.split("],[").size >= 3)       // at least 3 points
        assertTrue(d.maneuverPoint!!.endsWith(",0"))
        // Locale safety: coordinates must use a dot, never a comma, as the decimal separator.
        assertTrue(Regex("""-?\d+\.\d""").containsMatchIn(d.guideLineJson!!))
    }

    @Test fun `guide line is omitted when the segment is too short or the route too coarse`() {
        assertNull(SomeIpGuideLine.extras(listOf(LatLng(0.0, 0.0)), 0.0, 0.0, 0.0).guideLineJson)
        val poly = listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.0001))
        // Car essentially at the end: no room for a line.
        val total = app.vela.core.nav.RouteProjection.cumulative(poly).last()
        assertNull(SomeIpGuideLine.extras(poly, total, total, total).guideLineJson)
    }
}
