package top.izuna.foliamajor.lyrics.layout

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ring geometry for the `claddagh` visualizer, ported from folia-major's `claddaghLayout.ts`.
 *
 * The line is laid along an **ellipse**, not a circle: the desktop app projects a circle tilted 45°
 * onto the screen, which is why the vertical radius is `Rx * 0.707`. That projection is what makes
 * the ring read as lying flat in the scene instead of facing the viewer.
 *
 * Two rules keep the text readable:
 *
 *  - A character's rotation follows the tangent, normalized into ±90° so no glyph is ever upside
 *    down. Without that, the bottom half of the ring would be inverted.
 *  - The arc span is capped, and the whole line is scaled down when it would exceed it, so a long
 *    lyric does not wrap around the ring twice.
 */

/** Where one character sits on the ring. */
data class RingChar(
    val char: String,
    /** Radians along the ellipse. */
    val angle: Float,
    /** Rotation in degrees, normalized to ±90. */
    val rotationDeg: Float,
    /** 0 for the first character, 1 for the last — used to reveal in time order. */
    val progress: Float,
)

data class RingLayout(
    val chars: List<RingChar>,
    /** Horizontal radius in pixels. */
    val radiusX: Float,
    /** Vertical radius in pixels (the 45° projection). */
    val radiusY: Float,
)

object CladdaghRingPlanner {

    /** How much of the ellipse a single line may occupy, in radians. */
    private const val MAX_ARC_SPAN = 4.25f

    /** Vertical squash for a circle viewed at 45°. */
    private const val PROJECTION_RATIO = 0.707f

    /** Tracking between characters, as a fraction of the font size. */
    private const val BASE_TRACKING_EM = 0.18f

    /**
     * Normalizes an angle into ±90°.
     *
     * The tangent flips sign at the top and bottom of the ring; without folding it back, characters
     * on the lower half would be rendered upside down.
     */
    fun normalizeReadableAngle(degrees: Float): Float {
        var normalized = degrees
        while (normalized > 90f) normalized -= 180f
        while (normalized < -90f) normalized += 180f
        return normalized
    }

    /**
     * Lays [text] around the ring.
     *
     * [measureWidth] reports the rendered width of a character at the given pixel size; the planner
     * needs it to distribute characters by their real advance rather than assuming monospace.
     */
    fun plan(
        text: String,
        fontPx: Float,
        maxRadiusX: Float,
        measureWidth: (char: String, fontPx: Float) -> Float,
    ): RingLayout {
        val chars = splitGraphemes(text).filter { it.isNotBlank() }
        if (chars.isEmpty()) {
            return RingLayout(emptyList(), maxRadiusX, maxRadiusX * PROJECTION_RATIO)
        }

        val radiusX = maxRadiusX.coerceAtLeast(fontPx * 2f)
        val radiusY = radiusX * PROJECTION_RATIO

        // Cumulative advance gives each character's centre position along the arc.
        val advances = chars.map { measureWidth(it, fontPx) + fontPx * BASE_TRACKING_EM }
        val totalWidth = advances.sum()
        val totalSpan = totalWidth / radiusX

        // A line wider than the allowed arc is scaled down so it still fits on the ring.
        val scaleFactor = if (totalSpan > MAX_ARC_SPAN) MAX_ARC_SPAN / totalSpan else 1f
        val scaledWidth = totalWidth * scaleFactor

        var cursor = 0f
        val result = chars.mapIndexed { index, char ->
            val centre = cursor + advances[index] / 2f * scaleFactor
            // Start at the leftmost point of the arc and sweep right.
            val angle = (centre - scaledWidth / 2f) / radiusX
            cursor += advances[index] * scaleFactor

            RingChar(
                char = char,
                angle = angle,
                rotationDeg = normalizeReadableAngle(Math.toDegrees(angle.toDouble()).toFloat()),
                progress = if (chars.size <= 1) 0f else index.toFloat() / (chars.size - 1),
            )
        }

        return RingLayout(result, radiusX, radiusY)
    }

    /**
     * The screen position of a character on the ellipse.
     *
     * The arc is centred at the top of the ring (`-π/2`), which is where a line of text reads
     * naturally; sweeping from there keeps the middle of the line at the top.
     */
    fun positionOf(char: RingChar, radiusX: Float, radiusY: Float): Pair<Float, Float> {
        val angle = char.angle - (PI / 2).toFloat()
        return (radiusX * cos(angle)) to (radiusY * sin(angle))
    }

    private fun splitGraphemes(text: String): List<String> {
        if (text.isEmpty()) return emptyList()

        val result = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val count = Character.charCount(codePoint)
            result.add(text.substring(index, index + count))
            index += count
        }
        return result
    }
}
