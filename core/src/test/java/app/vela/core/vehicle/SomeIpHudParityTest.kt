package app.vela.core.vehicle

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.RoundaboutGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins Vela's windshield-HUD output to the values of the known-good BYD HUD sender, so a future
 * change to either is deliberate. The expected values are the independent oracle, transcribed from
 * that sender's own source (its field-28 arrow table and its frame field order), not produced by
 * calling Vela: a drift in Vela's table or frame fails here.
 *
 * Oracle (proven sender, read 2026-10-22): field-28 native arrow = {none 0, left 1, right 2,
 * sharp-left 1, sharp-right 2, slight-left 3, slight-right 5, U-turn 7/8 by driving side, straight
 * 11, everything glyph-less 99}; guidance frame field order = 2,6,7,8,9,10,16,26,28 wrapped as
 * outer field 1; clear = 2,6(=255),16(=1). Vela sends the native-arrow table and folds U-turn to 7
 * (right-hand traffic; its model has no U-turn side).
 */
class SomeIpHudParityTest {
    private fun m(type: ManeuverType, exit: Int? = null, clockwise: Boolean? = null) = Maneuver(
        type, "", LatLng(38.5449, -121.7405), 0.0, 0.0,
        roundabout = clockwise?.let { RoundaboutGeometry(90.0, it) }, roundaboutExit = exit,
    )

    @Test fun `field 28 matches the known-good native-arrow table`() {
        // The proven sender's hudArrow() outputs, mapped onto Vela's maneuver model.
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
            // glyph-less: the arrow is blank (99), the picture in field 8 carries it
            ManeuverType.ROUNDABOUT to 99,
            ManeuverType.EXIT_ROUNDABOUT to 99,
            ManeuverType.ARRIVE to 99,
            ManeuverType.UNKNOWN to 99,
            // keeps/forks/ramps follow their slight-turn side in Vela's own model
            ManeuverType.KEEP_LEFT to 3, ManeuverType.FORK_LEFT to 3, ManeuverType.RAMP_LEFT to 3,
            ManeuverType.KEEP_RIGHT to 5, ManeuverType.FORK_RIGHT to 5, ManeuverType.RAMP_RIGHT to 5,
        )
        assertEquals(ManeuverType.entries.toSet(), oracle.keys)
        for ((type, id) in oracle) {
            assertEquals("field-28 for $type", id, SomeIpHudFrame.maneuverId(m(type)))
        }
    }

    @Test fun `guidance frame field order matches the proven DirectTbtPayload`() {
        val frame = SomeIpHudFrame.guidance(f28 = 1, distanceMeters = 300, road = "Russell Blvd", etaHhMm = "14:05", png = byteArrayOf(1, 2))
        // Decode the outer wrap and read the inner field numbers in order.
        var i = 0
        fun varint(): Long { var s = 0; var r = 0L; while (true) { val b = frame[i++].toInt() and 0xff; r = r or ((b and 0x7f).toLong() shl s); if (b < 0x80) return r; s += 7 } }
        assertEquals(0x0AL, varint())           // outer field 1, length-delimited
        val innerLen = varint().toInt()
        val end = i + innerLen
        val order = ArrayList<Int>()
        while (i < end) {
            val tag = varint(); val num = (tag shr 3).toInt(); val wire = (tag and 0x7).toInt()
            order.add(num)
            if (wire == 2) { val len = varint().toInt(); i += len } else varint()
        }
        assertEquals(listOf(2, 6, 7, 8, 9, 10, 16, 26, 28), order)
    }

    @Test fun `clear frame matches the proven sender`() {
        val frame = SomeIpHudFrame.clear()
        var i = 0
        fun varint(): Long { var s = 0; var r = 0L; while (true) { val b = frame[i++].toInt() and 0xff; r = r or ((b and 0x7f).toLong() shl s); if (b < 0x80) return r; s += 7 } }
        assertEquals(0x0AL, varint())
        val innerLen = varint().toInt(); val end = i + innerLen
        val fields = HashMap<Int, Long>()
        while (i < end) { val tag = varint(); val num = (tag shr 3).toInt(); fields[num] = varint() }
        assertEquals(2L, fields[2]); assertEquals(255L, fields[6]); assertEquals(1L, fields[16])
        assertEquals(setOf(2, 6, 16), fields.keys)
    }
}
