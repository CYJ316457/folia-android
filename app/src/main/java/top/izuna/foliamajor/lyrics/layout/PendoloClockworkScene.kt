package top.izuna.foliamajor.lyrics.layout

import kotlin.math.min

/**
 * Theme-tinted colours for the clockwork, and the anchor geometry the gear train hangs from.
 *
 * Ported from `pendoloClockworkScene.ts`. Colours are packed ARGB `Int`s rather than Compose
 * `Color`s so the whole palette is a pure function that unit tests can assert on without a graphics
 * context; the renderer converts at the draw call.
 */
data class PendoloClockworkPalette(
    val decorOpacityMultiplier: Float,
    val primaryAlpha10: Int,
    val primaryAlpha15: Int,
    val accentAlpha35: Int,
    val gearPrimaryAlpha: Int,
    val gearPrimarySubtleAlpha: Int,
    val gearAccentAlpha: Int,
    val gearAccentStrongAlpha: Int,
    val planetGearAlpha: Int,
    val jewelFillColor: Int,
    val jewelStrokeColor: Int,
    val mainGearFill: Int,
    val balanceGearFill: Int,
    val secondGearFill: Int,
    val bevelRing: Int,
    val rivetStroke: Int,
    val guillocheAlt: Int,
    val genevaAlt: Int,
    val idlerStroke: Int,
    val idlerInner: Int,
)

/** Where every gear sits, relative to the wheel hub. */
data class PendoloClockworkAnchors(
    val balanceCx: Float,
    val balanceCy: Float,
    val balanceR: Float,
    val transCx: Float,
    val transCy: Float,
    val transR: Float,
    val secondGearCx: Float,
    val secondGearCy: Float,
    val secondGearR: Float,
    val idlerCx: Float,
    val idlerCy: Float,
    val idlerR: Float,
    val focalAxisEndRadius: Float,
    val orbitR: Float,
    val planetR: Float,
    val coverRadius: Float,
    val gradientR: Float,
)

/**
 * Packed-ARGB colour arithmetic.
 *
 * The desktop composes colours as `rgba(...)` strings. Strings are the wrong representation here:
 * every frame would re-parse them, and a palette entry is a constant per theme. Packing to `Int`
 * also makes the alpha rule — "multiply the base alpha by the decor multiplier" — a bit operation
 * instead of text formatting.
 */
object PendoloColor {

    /** Parses `#RGB`, `#RRGGBB` or `#AARRGGBB`; returns opaque white for anything else. */
    fun parse(hex: String, fallback: Int = 0xFFFFFFFF.toInt()): Int {
        val body = hex.trim().removePrefix("#")
        return when (body.length) {
            3 -> {
                val r = body[0].digit() ?: return fallback
                val g = body[1].digit() ?: return fallback
                val b = body[2].digit() ?: return fallback
                argb((r * 17), (g * 17), (b * 17), 255)
            }

            6 -> {
                val value = body.toLongOrNull(16) ?: return fallback
                (0xFF000000L or value).toInt()
            }

            8 -> body.toLongOrNull(16)?.toInt() ?: fallback

            else -> fallback
        }
    }

    /** Replaces the alpha channel, keeping RGB. */
    fun withAlpha(argb: Int, alpha: Float): Int {
        val byte = (alpha.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        return (argb and 0x00FFFFFF) or (byte shl 24)
    }

    private fun argb(r: Int, g: Int, b: Int, a: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun Char.digit(): Int? = when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        in 'A'..'F' -> this - 'A' + 10
        else -> null
    }
}

object PendoloClockworkScene {

    /**
     * Builds the palette for the current decor level.
     *
     * `SUBTLE` is not a different colour scheme: it is exactly this palette at 60% multiplier, which
     * is why the multiplier is carried on the palette instead of being applied at each call site.
     */
    fun buildPalette(
        primaryArgb: Int,
        accentArgb: Int,
        decor: PendoloGearDecor,
    ): PendoloClockworkPalette {
        val m = decor.opacityMultiplier
        return PendoloClockworkPalette(
            decorOpacityMultiplier = m,
            primaryAlpha10 = PendoloColor.withAlpha(primaryArgb, 0.10f * m),
            primaryAlpha15 = PendoloColor.withAlpha(primaryArgb, 0.15f * m),
            accentAlpha35 = PendoloColor.withAlpha(accentArgb, 0.35f * m),
            gearPrimaryAlpha = PendoloColor.withAlpha(primaryArgb, 0.42f * m),
            gearPrimarySubtleAlpha = PendoloColor.withAlpha(primaryArgb, 0.32f * m),
            gearAccentAlpha = PendoloColor.withAlpha(accentArgb, 0.58f * m),
            gearAccentStrongAlpha = PendoloColor.withAlpha(accentArgb, 0.72f * m),
            planetGearAlpha = PendoloColor.withAlpha(primaryArgb, 0.24f * m),
            jewelFillColor = PendoloColor.withAlpha(accentArgb, 0.28f * m),
            jewelStrokeColor = PendoloColor.withAlpha(accentArgb, 0.65f * m),
            mainGearFill = PendoloColor.withAlpha(accentArgb, 0.08f * m),
            balanceGearFill = PendoloColor.withAlpha(accentArgb, 0.10f * m),
            secondGearFill = PendoloColor.withAlpha(accentArgb, 0.06f * m),
            bevelRing = PendoloColor.withAlpha(primaryArgb, 0.18f * m),
            rivetStroke = PendoloColor.withAlpha(primaryArgb, 0.25f * m),
            guillocheAlt = PendoloColor.withAlpha(primaryArgb, 0.07f * m),
            genevaAlt = PendoloColor.withAlpha(primaryArgb, 0.06f * m),
            idlerStroke = PendoloColor.withAlpha(primaryArgb, 0.28f * m),
            idlerInner = PendoloColor.withAlpha(primaryArgb, 0.18f * m),
        )
    }

    /**
     * Gear positions for a given wheel geometry.
     *
     * Every offset is a fraction of `baseRadius`, so the train scales with the wheel instead of
     * needing its own layout pass at each screen size. The `focalAxisEndRadius` stops the focal
     * axis just inside the lyric ring so the arrow never pokes through the text.
     */
    fun anchors(
        centerX: Float,
        centerY: Float,
        baseRadius: Float,
        lyricRingRadius: Float,
    ): PendoloClockworkAnchors {
        val balanceCx = centerX + baseRadius * 0.2f
        val balanceCy = centerY - baseRadius * 0.75f
        val balanceR = baseRadius * 0.28f

        val transCx = centerX + baseRadius * 0.32f
        val transCy = centerY + baseRadius * 0.78f
        val transR = baseRadius * 0.34f

        val secondGearCx = centerX + baseRadius * 0.68f
        val secondGearCy = centerY + baseRadius * 0.76f
        val secondGearR = baseRadius * 0.16f

        val idlerCx = (transCx + secondGearCx) * 0.5f + baseRadius * 0.04f
        val idlerCy = (transCy + secondGearCy) * 0.5f - baseRadius * 0.02f
        val idlerR = baseRadius * 0.08f

        val focalAxisEndRadius = min(
            lyricRingRadius - maxOf(14f, baseRadius * 0.025f),
            lyricRingRadius,
        )

        return PendoloClockworkAnchors(
            balanceCx = balanceCx,
            balanceCy = balanceCy,
            balanceR = balanceR,
            transCx = transCx,
            transCy = transCy,
            transR = transR,
            secondGearCx = secondGearCx,
            secondGearCy = secondGearCy,
            secondGearR = secondGearR,
            idlerCx = idlerCx,
            idlerCy = idlerCy,
            idlerR = idlerR,
            focalAxisEndRadius = focalAxisEndRadius,
            orbitR = baseRadius * 0.52f,
            planetR = baseRadius * 0.16f,
            coverRadius = baseRadius * 0.88f,
            gradientR = baseRadius * 1.65f,
        )
    }
}
