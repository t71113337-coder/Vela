package app.vela.core.vehicle

import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType

/**
 * The maneuver id (field 28 of the HUD road-info frame) that a BYD windshield HUD reads over the
 * SOME/IP gateway (SPEC 10.9). Two renderers read two vocabularies, so the table is chosen per car.
 * Left, right, the slight turns and straight are the same in both. These ids belong to the SOME/IP
 * HUD only; the FCE cluster has its own ([FceIcons]).
 *
 * [SomeIpHudFrame] sends the native-arrow table, which matches the proven windshield sender's
 * field-28 values 1 to 1 (left 1, right 2, slight 3/5, sharp folded 1/2, straight 11, U-turn 7,
 * everything glyph-less 99). The one documented gap: the proven sender splits a U-turn into 7 for
 * right-hand traffic and 8 for left-hand traffic, while Vela sends 7 because its maneuver model
 * carries no U-turn side. That is correct for right-hand-traffic markets and would draw a
 * right-hand U-turn glyph in a left-hand-traffic one. Fixing it needs a side signal in the core
 * [Maneuver] model, which is out of scope here.
 */
object SomeIpHudArrows {
    enum class Table {
        /** The ids BYD's own map app sends (the AutoNavi maneuver icon ids). */
        LAUNCHERMAP,
        /** The ids third-party senders use for the HUD's built-in arrows: sharp turns folded into
         *  plain ones, and 99 (no glyph) for anything without one. */
        NATIVE_ARROW,
    }

    const val NONE = 0
    const val NO_GLYPH = 99

    fun of(m: Maneuver?, table: Table): Int {
        val type = m?.type ?: return NONE
        val clockwise = m.roundabout?.clockwise == true
        val native = table == Table.NATIVE_ARROW
        return when (type) {
            ManeuverType.TURN_LEFT -> 1
            ManeuverType.TURN_RIGHT -> 2
            ManeuverType.SLIGHT_LEFT, ManeuverType.KEEP_LEFT, ManeuverType.FORK_LEFT, ManeuverType.RAMP_LEFT -> 3
            ManeuverType.SLIGHT_RIGHT, ManeuverType.KEEP_RIGHT, ManeuverType.FORK_RIGHT, ManeuverType.RAMP_RIGHT -> 5
            ManeuverType.SHARP_LEFT -> if (native) 1 else 7
            ManeuverType.SHARP_RIGHT -> if (native) 2 else 8
            ManeuverType.UTURN -> if (native) 7 else 9
            ManeuverType.DEPART, ManeuverType.CONTINUE, ManeuverType.STRAIGHT, ManeuverType.MERGE -> 11
            ManeuverType.ROUNDABOUT -> if (native) NO_GLYPH else if (clockwise) 14 else 13
            ManeuverType.EXIT_ROUNDABOUT -> if (native) NO_GLYPH else if (clockwise) 23 else 24
            ManeuverType.ARRIVE -> if (native) NO_GLYPH else 48
            ManeuverType.UNKNOWN -> if (native) NO_GLYPH else NONE
        }
    }
}
