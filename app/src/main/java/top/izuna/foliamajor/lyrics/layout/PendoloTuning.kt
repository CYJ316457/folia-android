package top.izuna.foliamajor.lyrics.layout

/**
 * Tunable parameters for `pendolo`, mirroring `PendoloTuning` in the desktop app's `src/types.ts`.
 *
 * The desktop exposes all of these in a settings panel. This client keeps the desktop defaults and
 * does not surface a panel yet: the defaults are what the mode is designed around, and a tuning UI
 * with no live preview would be worse than none.
 */
data class PendoloTuning(
    /** Arc radius as a fraction of the smaller viewport side. */
    val arcRadius: Float = 0.42f,
    /** Total sweep of the lyric arc, in degrees. */
    val arcAngleDeg: Float = 100f,
    /** Wheel centre as a fraction of the viewport. 0 puts the hub on the left edge. */
    val wheelCenterX: Float = 0f,
    val wheelCenterY: Float = 0.5f,
    /** How sharply the escapement snaps when the focal line changes. */
    val tickSnappiness: Float = 2f,
    val activeScale: Float = 1.25f,
    val showGearDecor: PendoloGearDecor = PendoloGearDecor.SUBTLE,
    val showCenterGradient: Boolean = true,
    val showCoverOnWatchFace: Boolean = false,
    val enableLineGlow: Boolean = false,
)

/** How much of the clockwork is drawn. `NONE` leaves only the lyric arc. */
enum class PendoloGearDecor {
    NONE,
    SUBTLE,
    FULL,
    ;

    /** Decor opacity multiplier: the subtle preset is the same drawing at 60%. */
    val opacityMultiplier: Float
        get() = when (this) {
            NONE -> 0f
            SUBTLE -> 0.6f
            FULL -> 1f
        }
}
