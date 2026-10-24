package app.vela.core.vehicle

import app.vela.core.model.Maneuver

/**
 * The windshield-HUD road-info frame a BYD head unit's glass reads over the SOME/IP gateway, and
 * the IDs it is published on (SPEC 10.9). This builds the wire bytes; the gateway bind and the
 * Binder transaction live in `:app` (`SomeIpHudTransport`). The field numbers and the frame shape
 * are the documented HUD_ROAD_INFO contract (HudRoadInfoNotifyStruct), expressed here as a small
 * protobuf writer so the frame is byte-exact and testable without a device.
 *
 * The frame is the minimal proven one: an inner message with the counter, a layout byte, an empty
 * lane field, the maneuver picture, the distance, the next road, the guiding status, the ETA and
 * the maneuver id, wrapped as outer field 1. The maneuver id uses [SomeIpHudArrows]; the FCE
 * cluster glyphs ([FceIcons]) are a different vocabulary and are never used here.
 */
/**
 * Whether the windshield HUD should be showing guidance right now, as opposed to a clear frame
 * (SPEC 10.9). It guides only while the feature is on and the drive is actively navigating with a
 * maneuver to show; a stop, an arrival, a pause or a reroute with no maneuver yet all fall through
 * to not-guiding, which the controller turns into a single clear so the glass never keeps a stale
 * arrow. Pure, so the dispatch and the stale-clear transitions are tested without a device. The FCE
 * cluster has the same shape ([clusterNavOutput]); it keeps the view open on a pause where the glass
 * clears it.
 */
fun someIpHudGuiding(
    enabled: Boolean,
    navigating: Boolean,
    paused: Boolean,
    arrived: Boolean,
    hasManeuver: Boolean,
): Boolean = enabled && navigating && !paused && !arrived && hasManeuver

/**
 * The extra road-frame fields a richer windshield renderer can read (SPEC 10.9, "rich frame"). All
 * optional: a field is written only when the value is present, so a frame built with no extras is the
 * minimal one. These mirror what a known-good third-party sender publishes (real route progress and
 * a world-anchored guide line), sourced from Vela's own route and progress, never faked. The vehicle
 * point is the route-snapped position, not a raw fix, so it needs no extra location plumbing.
 */
data class RoadExtras(
    val remainingMeters: Int? = null,   // f3
    val remainingSeconds: Int? = null,  // f4
    val remainingTimeText: String? = null, // f27 "12 min"
    val carLon: Double? = null,         // f19
    val carLat: Double? = null,         // f20
    val headingDeg: Double? = null,     // f32 route bearing at the car, [0,360)
    val progress: Double? = null,       // f33 fraction of the route traveled, 0..1
    val guideLineJson: String? = null,  // f30 "[[lon,lat,0],...]"
    val maneuverPoint: String? = null,  // f31 "lon,lat,0"
    val laneCount: Int = 0,             // f5
)

object SomeIpHudFrame {
    /** startSomeIpService argument: service 0x010A, instance 0x0001. */
    const val SERVICE_ID = 0x000B010A00010000L
    /** fireEvent topic: HUD_ROAD_INFO event 0x8001. */
    const val TOPIC_ROAD_INFO = 0x0004010A00018001L
    /** fireEvent topic: HUD_NAVIGATIONMAP event 0x8003 (the 300x180 map window). */
    const val TOPIC_MAP = 0x0004010A00018003L

    /** A guiding frame: status 2, the arrow picture in field 8, distance, road and ETA. [png] is
     *  the maneuver arrow as PNG bytes (empty when none); distance is floored at 11 m because 0..10
     *  shows a "now" glyph on some clusters; the road name is capped at 200 UTF-8 bytes, cut on a
     *  character boundary, which matches the proven sender's byte cap (a char cap overruns it for
     *  multi-byte scripts). */
    fun guidance(f28: Int, distanceMeters: Int, road: String, etaHhMm: String, png: ByteArray?, counter: Int = 2): ByteArray {
        val inner = ArrayList<Byte>(256)
        putVarintField(inner, 2, counter.toLong())        // Counter
        putVarintField(inner, 6, 1)                       // CurrentRoadLevel reused as layout: 6 = picture in 7/8
        putLenField(inner, 7, ByteArray(0))               // PermissibleDirection (lane picture): empty
        putLenField(inner, 8, png ?: ByteArray(0))        // RecommendedDrivingDirections picture (the arrow PNG)
        putVarintField(inner, 9, distanceMeters.coerceAtLeast(DIST_FLOOR_M).toLong())
        putLenField(inner, 10, roadBytes(road))
        putVarintField(inner, 16, 2)                      // NavigatingStatus: guiding
        putLenField(inner, 26, etaHhMm.toByteArray(Charsets.UTF_8)) // ETAInfoTime "HH:mm"
        putVarintField(inner, 28, f28.toLong())           // RecommendedDrivingDirectionsId
        return wrap(inner)
    }

    /** The enriched guiding frame (SPEC 10.9, "rich frame"), published when the dev dial is on. It is
     *  the minimal frame plus whatever of [extras] is present: remaining route, route progress, a
     *  world-anchored guide line and the route-snapped vehicle point. It matches a known-good
     *  third-party sender's field set, which keeps a richer renderer's AR layer fed. Field order
     *  follows that sender (no field 6; a lane count in field 5; no 11 m distance floor). Still
     *  car-unverified. */
    fun guidanceRich(f28: Int, distanceMeters: Int, road: String, etaHhMm: String, png: ByteArray?, extras: RoadExtras, counter: Int = 2): ByteArray {
        val inner = ArrayList<Byte>(512)
        putVarintField(inner, 2, counter.toLong())        // Counter
        putVarintField(inner, 16, 2)                      // NavigatingStatus: guiding
        putVarintField(inner, 5, extras.laneCount.toLong()) // lane count (0 when unknown)
        extras.remainingMeters?.let { putVarintField(inner, 3, it.coerceAtLeast(0).toLong()) }
        extras.remainingSeconds?.let { putVarintField(inner, 4, it.coerceAtLeast(0).toLong()) }
        putLenField(inner, 8, png ?: ByteArray(0))        // maneuver picture
        putVarintField(inner, 9, distanceMeters.coerceAtLeast(0).toLong()) // no 11 m floor in rich mode
        if (road.isNotEmpty()) putLenField(inner, 10, roadBytes(road)) // omit empty strings in rich mode
        if (etaHhMm.isNotEmpty()) putLenField(inner, 26, etaHhMm.toByteArray(Charsets.UTF_8)) // ETA "HH:mm"
        extras.remainingTimeText?.takeIf { it.isNotEmpty() }?.let { putStringField(inner, 27, it) }
        putVarintField(inner, 28, f28.toLong())
        if (extras.carLon != null && extras.carLat != null) {
            putFixed64Field(inner, 19, extras.carLon)
            putFixed64Field(inner, 20, extras.carLat)
        }
        extras.guideLineJson?.takeIf { it.isNotEmpty() }?.let { putStringField(inner, 30, it) }
        extras.maneuverPoint?.takeIf { it.isNotEmpty() }?.let { putStringField(inner, 31, it) }
        extras.headingDeg?.let { putFixed64Field(inner, 32, it) }
        extras.progress?.let { putFixed64Field(inner, 33, it) }
        return wrap(inner)
    }

    /** The HUD_NAVIGATIONMAP window frame (event 0x8003): outer field 1 is the Base64 of a PNG. */
    fun mapWindow(base64Png: String): ByteArray {
        val inner = ArrayList<Byte>(base64Png.length + 4)
        putLenField(inner, 1, base64Png.toByteArray(Charsets.US_ASCII))
        return inner.toByteArray()
    }

    /** The clear frame: status idle, layout 255. The glass blanks the arrow and the readouts. */
    fun clear(): ByteArray {
        val inner = ArrayList<Byte>(8)
        putVarintField(inner, 2, 2)
        putVarintField(inner, 6, 255)                     // layout: clear
        putVarintField(inner, 16, 1)                      // NavigatingStatus: idle
        return wrap(inner)
    }

    /** The rich-mode clear frame: `{2=2, 16=1, 5=0}`, matching the known-good sender (no field 6). */
    fun clearRich(): ByteArray {
        val inner = ArrayList<Byte>(8)
        putVarintField(inner, 2, 2)
        putVarintField(inner, 16, 1)                      // NavigatingStatus: idle
        putVarintField(inner, 5, 0)
        return wrap(inner)
    }

    /** The maneuver id for field 28, from the shared table (native-arrow vocabulary, which is what
     *  the proven picture-in-8 senders use; single arrows match the stock table either way). */
    fun maneuverId(m: Maneuver?): Int = SomeIpHudArrows.of(m, SomeIpHudArrows.Table.NATIVE_ARROW)

    const val DIST_FLOOR_M = 11
    const val ROAD_MAX_BYTES = 200

    // Encode the road and cap it at ROAD_MAX_BYTES, backing off any split multi-byte character, so
    // an Arabic or CJK name is truncated like the proven sender (a byte cap, not a char cap).
    private fun roadBytes(road: String): ByteArray {
        val utf8 = road.toByteArray(Charsets.UTF_8)
        if (utf8.size <= ROAD_MAX_BYTES) return utf8
        var cut = ROAD_MAX_BYTES
        while (cut > 0 && (utf8[cut].toInt() and 0xC0) == 0x80) cut--  // 0x80..0xBF = UTF-8 continuation byte
        return utf8.copyOf(cut)
    }

    // The inner message rides as outer field 1 (length-delimited): 0x0A, varint(len), bytes.
    private fun wrap(inner: List<Byte>): ByteArray {
        val out = ArrayList<Byte>(inner.size + 4)
        putTag(out, 1, WIRE_LEN)
        putVarint(out, inner.size.toLong())
        out.addAll(inner)
        return out.toByteArray()
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_LEN = 2

    private fun putTag(out: MutableList<Byte>, field: Int, wire: Int) = putVarint(out, ((field shl 3) or wire).toLong())

    private fun putVarintField(out: MutableList<Byte>, field: Int, value: Long) {
        putTag(out, field, WIRE_VARINT)
        putVarint(out, value)
    }

    private fun putLenField(out: MutableList<Byte>, field: Int, bytes: ByteArray) {
        putTag(out, field, WIRE_LEN)
        putVarint(out, bytes.size.toLong())
        for (b in bytes) out.add(b)
    }

    private fun putStringField(out: MutableList<Byte>, field: Int, s: String) =
        putLenField(out, field, s.toByteArray(Charsets.UTF_8))

    private const val WIRE_FIXED64 = 1

    // proto fixed64 / double: 8 bytes, little-endian IEEE-754.
    private fun putFixed64Field(out: MutableList<Byte>, field: Int, value: Double) {
        putTag(out, field, WIRE_FIXED64)
        var bits = java.lang.Double.doubleToRawLongBits(value)
        repeat(8) { out.add((bits and 0xFF).toByte()); bits = bits ushr 8 }
    }

    // Unsigned LEB128, as protobuf writes a varint. Values here are small and non-negative.
    private fun putVarint(out: MutableList<Byte>, value: Long) {
        var x = value
        while (true) {
            val b = (x and 0x7F).toInt()
            x = x ushr 7
            if (x != 0L) out.add((b or 0x80).toByte()) else { out.add(b.toByte()); return }
        }
    }
}
