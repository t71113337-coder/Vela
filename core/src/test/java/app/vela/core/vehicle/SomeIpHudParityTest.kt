package app.vela.core.vehicle

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.RoundaboutGeometry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins Vela's windshield-HUD frame to the wire bytes the documented SOME/IP HUD contract specifies
 * (SPEC 10.9). The expected arrays are the independent oracle: each was hand-encoded here from that
 * contract (field number, wire type, LEB128 varint, length-delimited bytes, inner message wrapped
 * as outer field 1), not produced by calling Vela and not read from any other app's serializer. A
 * drift in Vela's field order, wire type, value, encoding, distance floor or road truncation fails
 * one of these. These vectors are byte-exact against the contract; they do not claim every possible
 * payload or an on-vehicle render.
 *
 * Oracle (the documented SOME/IP HUD wire contract, SPEC 10.9): MIN_DISTANCE 11 m,
 * road capped at 200 UTF-8 bytes on a character boundary, field-28 native arrow table {none 0,
 * left 1, right 2, sharp-left 1, sharp-right 2, slight-left 3, slight-right 5, U-turn 7/8 by
 * driving side, straight 11, glyph-less 99}; guidance field order 2,6,7,8,9,10,16,26,28 wrapped as
 * outer field 1; clear = 2,6(=255),16(=1). Vela sends the native-arrow table and folds U-turn to 7
 * (right-hand traffic; its Maneuver model carries no U-turn side, so it cannot select 8).
 */
class SomeIpHudParityTest {
    private fun m(type: ManeuverType, exit: Int? = null, clockwise: Boolean? = null) = Maneuver(
        type, "", LatLng(38.5449, -121.7405), 0.0, 0.0,
        roundabout = clockwise?.let { RoundaboutGeometry(90.0, it) }, roundaboutExit = exit,
    )

    // Read the varint at [i] in [b], returning (value, nextIndex).
    private fun varint(b: ByteArray, i0: Int): Pair<Long, Int> {
        var i = i0; var s = 0; var r = 0L
        while (true) { val x = b[i++].toInt() and 0xff; r = r or ((x and 0x7f).toLong() shl s); if (x < 0x80) return r to i; s += 7 }
    }

    // Decode the inner message (unwrap outer field 1) into field-number -> raw payload.
    private fun inner(frame: ByteArray): Map<Int, ByteArray> {
        var (tag, i) = varint(frame, 0)
        require(tag == 0x0AL) { "outer tag not field-1 len-delimited" }
        val (len, j) = varint(frame, i); i = j
        val end = i + len.toInt()
        val out = LinkedHashMap<Int, ByteArray>()
        while (i < end) {
            val (t, k) = varint(frame, i); i = k
            val num = (t shr 3).toInt(); val wire = (t and 0x7).toInt()
            if (wire == 2) { val (l, p) = varint(frame, i); i = p; out[num] = frame.copyOfRange(i, i + l.toInt()); i += l.toInt() }
            else { val (v, p) = varint(frame, i); i = p; out[num] = encodeVarint(v) }
        }
        return out
    }

    private fun encodeVarint(v: Long): ByteArray {
        val o = ArrayList<Byte>(); var x = v
        while (true) { val b = (x and 0x7f).toInt(); x = x ushr 7; if (x != 0L) o.add((b or 0x80).toByte()) else { o.add(b.toByte()); return o.toByteArray() } }
    }

    @Test fun `guidance frame is byte-for-byte the proven wire bytes`() {
        // f28=1 (left), 300 m, road "Main", ETA "14:05", arrow PNG {0x11,0x22,0x33}, counter 2.
        val frame = SomeIpHudFrame.guidance(f28 = 1, distanceMeters = 300, road = "Main", etaHhMm = "14:05", png = byteArrayOf(0x11, 0x22, 0x33))
        val expected = byteArrayOf(
            0x0A, 0x22,                                     // outer field 1, inner length 34
            0x10, 0x02,                                     // f2 counter = 2
            0x30, 0x01,                                     // f6 layout = 1 (picture)
            0x3A, 0x00,                                     // f7 lane bytes = empty
            0x42, 0x03, 0x11, 0x22, 0x33,                   // f8 arrow PNG bytes
            0x48, 0xAC.toByte(), 0x02,                      // f9 distance = 300
            0x52, 0x04, 0x4D, 0x61, 0x69, 0x6E,             // f10 road = "Main"
            0x80.toByte(), 0x01, 0x02,                      // f16 status = 2 (guiding)
            0xD2.toByte(), 0x01, 0x05, 0x31, 0x34, 0x3A, 0x30, 0x35, // f26 ETA = "14:05"
            0xE0.toByte(), 0x01, 0x01,                      // f28 maneuver id = 1
        )
        assertArrayEquals(expected, frame)
    }

    @Test fun `clear frame is byte-for-byte the proven wire bytes`() {
        val expected = byteArrayOf(
            0x0A, 0x08,                 // outer field 1, inner length 8
            0x10, 0x02,                 // f2 counter = 2
            0x30, 0xFF.toByte(), 0x01,  // f6 layout = 255 (clear)
            0x80.toByte(), 0x01, 0x01,  // f16 status = 1 (idle)
        )
        assertArrayEquals(expected, SomeIpHudFrame.clear())
    }

    @Test fun `distance is floored at the minimum and otherwise passed through`() {
        fun f9(meters: Int): Long = varint(inner(SomeIpHudFrame.guidance(1, meters, "r", "", null))[9]!!, 0).first
        assertEquals(11L, f9(0))    // below floor
        assertEquals(11L, f9(10))   // just below floor
        assertEquals(11L, f9(11))   // at floor
        assertEquals(12L, f9(12))   // above floor: passed through
        assertEquals(131072L, f9(131072))
    }

    @Test fun `field 8 is a length-delimited bytes field carrying the arrow, never a packed int`() {
        val png = byteArrayOf(0x7F, 0x00, 0x42.toByte())
        val frame = SomeIpHudFrame.guidance(1, 50, "r", "", png)
        // Locate the f8 tag and assert wire type 2 (length-delimited), then the exact payload.
        var (_, i) = varint(frame, 0); val (len, j) = varint(frame, i); i = j
        var tagByte = -1
        var k = i
        val end = i + len.toInt()
        while (k < end) {
            val (t, n) = varint(frame, k)
            if ((t shr 3).toInt() == 8) { tagByte = (t and 0x7).toInt(); k = n; break }
            k = n
            if ((t and 0x7).toInt() == 2) { val (l, p) = varint(frame, k); k = p + l.toInt() } else { val (_, p) = varint(frame, k); k = p }
        }
        assertEquals("f8 wire type must be 2 (bytes)", 2, tagByte)
        assertArrayEquals(png, inner(frame)[8])
    }

    @Test fun `road is capped at 200 bytes on a character boundary, not 200 chars`() {
        // 100 CJK chars = 300 UTF-8 bytes. A byte cap keeps 66 whole chars (198 bytes); a char cap
        // would wrongly keep all 100 (600 bytes). The cut never splits a multi-byte character.
        val road = "中".repeat(100)
        val f10 = inner(SomeIpHudFrame.guidance(1, 50, road, "", null))[10]!!
        assertEquals(198, f10.size)
        assertEquals("中".repeat(66), String(f10, Charsets.UTF_8))
        // An ASCII road under the cap is untouched.
        assertArrayEquals("Main St".toByteArray(), inner(SomeIpHudFrame.guidance(1, 50, "Main St", "", null))[10])
    }

    @Test fun `field 28 matches the known-good native-arrow table`() {
        val oracle = mapOf(
            ManeuverType.TURN_LEFT to 1,
            ManeuverType.TURN_RIGHT to 2,
            ManeuverType.SHARP_LEFT to 1,   // folded to a plain left
            ManeuverType.SHARP_RIGHT to 2,  // folded to a plain right
            ManeuverType.SLIGHT_LEFT to 3,
            ManeuverType.SLIGHT_RIGHT to 5,
            ManeuverType.UTURN to 7,        // right-hand traffic; the proven sender uses 8 for left-hand
            ManeuverType.STRAIGHT to 11,
            ManeuverType.CONTINUE to 11,
            ManeuverType.DEPART to 11,
            ManeuverType.MERGE to 11,
            ManeuverType.ROUNDABOUT to 99,
            ManeuverType.EXIT_ROUNDABOUT to 99,
            ManeuverType.ARRIVE to 99,
            ManeuverType.UNKNOWN to 99,
            ManeuverType.KEEP_LEFT to 3, ManeuverType.FORK_LEFT to 3, ManeuverType.RAMP_LEFT to 3,
            ManeuverType.KEEP_RIGHT to 5, ManeuverType.FORK_RIGHT to 5, ManeuverType.RAMP_RIGHT to 5,
        )
        assertEquals(ManeuverType.entries.toSet(), oracle.keys)
        for ((type, id) in oracle) {
            assertEquals("field-28 for $type", id, SomeIpHudFrame.maneuverId(m(type)))
        }
    }
}
