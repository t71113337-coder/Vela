package app.vela.vehicle

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import app.vela.core.model.ManeuverType
import app.vela.core.vehicle.SomeIpHudFrame
import app.vela.service.NavGlyphs
import java.io.ByteArrayOutputStream

/**
 * Renders the 300x180 HUD map-window image (SOME/IP event 0x8003, SPEC 10.9), the "rich frame"
 * companion a known-good third-party sender publishes alongside the road frame. Vela draws a maneuver
 * card (arrow, distance, road, remaining) rather than a live map: a renderer that waits for the map
 * window gets a frame, without a map engine on this path. The output is the ready 0x8003 frame bytes
 * ([SomeIpHudFrame.mapWindow] around the Base64 PNG). Car-unverified; off by default behind the dev
 * dial. Call it off the main thread (the transport worker); one render per content change.
 */
object SomeIpMapCard {
    const val WIDTH = 300
    const val HEIGHT = 180

    /** What the card shows. Value equality drives the render-once-per-change cache in the transport. */
    data class Spec(val maneuver: ManeuverType?, val distanceM: Int, val road: String, val remaining: String?)

    private const val BG = 0xFF0A0E14.toInt()
    private const val FG = 0xFFFFFFFF.toInt()
    private const val DIM = 0xFFB8C0CC.toInt()
    private const val ACCENT = 0xFF35E0C8.toInt()

    /** The 0x8003 frame for [spec], or a black frame when [spec] is null (used on clear). */
    fun frame(spec: Spec?): ByteArray = SomeIpHudFrame.mapWindow(if (spec == null) blackBase64 else renderBase64(spec))

    private val blackBase64: String by lazy {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.BLACK)
        base64(bmp)
    }

    private fun renderBase64(spec: Spec): String {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)
        // Arrow glyph on the left.
        val glyph = runCatching { NavGlyphs.bitmap(spec.maneuver ?: ManeuverType.STRAIGHT, GLYPH, Color.TRANSPARENT) }.getOrNull()
        if (glyph != null) {
            val dst = Rect(PAD, (HEIGHT - GLYPH) / 2, PAD + GLYPH, (HEIGHT + GLYPH) / 2)
            c.drawBitmap(glyph, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        val textX = (PAD + GLYPH + PAD).toFloat()
        // Distance, big.
        c.drawText(distanceText(spec.distanceM), textX, 72f, paint(ACCENT, 52f, bold = true))
        // Road name, clipped to the card width.
        if (spec.road.isNotEmpty()) c.drawText(ellipsize(spec.road, textX, 30f), textX, 116f, paint(FG, 30f))
        // Remaining route, dim.
        if (!spec.remaining.isNullOrEmpty()) c.drawText(spec.remaining, textX, 152f, paint(DIM, 24f))
        return base64(bmp)
    }

    private fun distanceText(m: Int): String {
        val d = m.coerceAtLeast(0)
        return if (d >= 1000) String.format(java.util.Locale.US, "%.1f km", d / 1000.0) else "$d m"
    }

    private fun ellipsize(s: String, x: Float, textSize: Float): String {
        val p = paint(FG, textSize)
        val maxW = WIDTH - x - PAD
        if (p.measureText(s) <= maxW) return s
        var cut = s.length
        while (cut > 1 && p.measureText(s.substring(0, cut) + "…") > maxW) cut--
        return s.substring(0, cut) + "…"
    }

    private fun paint(color: Int, size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        if (bold) isFakeBoldText = true
    }

    private fun base64(bmp: Bitmap): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private const val PAD = 12
    private const val GLYPH = 120
}
