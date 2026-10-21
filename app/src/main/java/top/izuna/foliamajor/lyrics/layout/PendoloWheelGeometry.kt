package top.izuna.foliamajor.lyrics.layout

import top.izuna.foliamajor.lyrics.Line
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * One lyric line placed on the wheel arc.
 *
 * [angleRad] is the unwrapped angle from the focal axis (0 = straight right, positive = below).
 * [x]/[y] are canvas pixels. Negative angles belong to lines that have already been sung, which sit
 * above the focal line as the wheel turns.
 */
data class PendoloLineItem(
    val line: Line,
    val index: Int,
    val angleRad: Float,
    val angleDeg: Float,
    val x: Float,
    val y: Float,
    val isActive: Boolean,
    val distanceFromActive: Int,
    val alpha: Float,
    val scale: Float,
)

/**
 * Places lyric lines on the pendulum's escapement arc.
 *
 * Ported from `src/components/visualizer/pendolo/pendoloGeometry.ts`. The geometry is the whole
 * identity of this mode: lines sit on a circle whose hub is at the left edge, the current line at
 * angle 0, upcoming lines curving down and past lines curving up. The camera never moves — the
 * *wheel rotates* instead, which is why this is a ring layout rather than a scrolling list.
 *
 * Two things here are not obvious and are worth keeping exactly:
 *
 *  - Spacing between neighbours is `max(angleStep, (h1 + h2 + 24) / 2r)`. The angle step alone
 *    would let a two-line-tall block overlap its neighbour; the height term converts a pixel gap
 *    into the angle that produces it on this radius. Lines are therefore unevenly spaced, which is
 *    the point — the desktop calls it a "wheel", not a fixed-pitch list.
 *  - [targetLineIndex] is a `Double`, not an `Int`. The escapement spring animates it between lines,
 *    and the half-step value it passes through on the way (`lastIndex + 0.5`) is what makes a seek
 *    past the end keep the last line on screen instead of snapping to nothing.
 */
object PendoloWheelGeometry {

    /** Lines visible across the arc window. The desktop uses 9 and so does this. */
    const val VISIBLE_WINDOW_COUNT = 9

    /** Extra gap between neighbouring lines, in pixels, before converting to an angle. */
    private const val LINE_GAP_PX = 24f

    /**
     * Angular step between adjacent lines when no block heights are known.
     *
     * Separated out because the renderer needs the same value for the wheel rotation: a line change
     * rotates the wheel by exactly one step, and deriving it in two places would let them drift.
     */
    fun angleStepRad(tuning: PendoloTuning = PendoloTuning()): Float {
        val totalArcRad = (tuning.arcAngleDeg * PI / 180.0).toFloat()
        return totalArcRad / max(1, VISIBLE_WINDOW_COUNT - 1)
    }

    /**
     * Lays out the visible window of lines.
     *
     * [lineBlockHeightsPx] may be shorter than [lines] — the renderer only measures lines near the
     * focal point. Missing entries are treated as zero height, which falls back to the plain angle
     * step for that gap.
     */
    fun layout(
        lines: List<Line>,
        targetLineIndex: Double,
        escapementAngleOffsetRad: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        tuning: PendoloTuning = PendoloTuning(),
        radiusOffsetPx: Float = 0f,
        lineBlockHeightsPx: List<Float> = emptyList(),
    ): List<PendoloLineItem> {
        if (lines.isEmpty()) return emptyList()

        val centerX = viewportWidth * tuning.wheelCenterX
        val centerY = viewportHeight * tuning.wheelCenterY
        val baseRadius = min(viewportWidth, viewportHeight) * tuning.arcRadius + radiusOffsetPx
        val stepRad = angleStepRad(tuning)

        // A fractional target means "between lines"; only a whole in-range index is a real focus.
        val isWholeTarget = targetLineIndex == floor(targetLineIndex) && targetLineIndex.isFinite()
        val activeIndex = if (isWholeTarget && targetLineIndex >= 0 && targetLineIndex < lines.size) {
            targetLineIndex.toInt()
        } else {
            -1
        }

        val centerRef = floor(if (targetLineIndex >= 0) targetLineIndex else 0.0)
            .coerceIn(0.0, (lines.size - 1).toDouble())
            .toInt()
        val windowStart = max(0, centerRef - 8)
        val windowEnd = min(lines.size - 1, centerRef + 8)

        // Unwrapped angles relative to the focal line. Filled outward from the target so the
        // height-aware spacing accumulates in both directions from a known zero.
        val visualAngles = HashMap<Int, Float>()

        if (isWholeTarget && activeIndex in windowStart..windowEnd) {
            visualAngles[activeIndex] = 0f

            for (index in activeIndex + 1..windowEnd) {
                val previousHeight = lineBlockHeightsPx.getOrElse(index - 1) { 0f }
                val currentHeight = lineBlockHeightsPx.getOrElse(index) { 0f }
                val spacing = max(stepRad, (previousHeight + currentHeight + LINE_GAP_PX) / (2f * baseRadius))
                visualAngles[index] = (visualAngles[index - 1] ?: 0f) + spacing
            }

            for (index in activeIndex - 1 downTo windowStart) {
                val nextHeight = lineBlockHeightsPx.getOrElse(index + 1) { 0f }
                val currentHeight = lineBlockHeightsPx.getOrElse(index) { 0f }
                val spacing = max(stepRad, (nextHeight + currentHeight + LINE_GAP_PX) / (2f * baseRadius))
                visualAngles[index] = (visualAngles[index + 1] ?: 0f) - spacing
            }
        }

        val items = mutableListOf<PendoloLineItem>()
        for (index in windowStart..windowEnd) {
            val line = lines.getOrNull(index) ?: continue
            val distanceIndex = index - targetLineIndex
            val rawAngle = (visualAngles[index] ?: (distanceIndex * stepRad).toFloat()) + escapementAngleOffsetRad

            // Only the right semicircle is drawn. Lines past ±90° are behind the hub, where the
            // arc has already curved out of the viewport.
            if (abs(rawAngle) >= PI / 2) continue

            val x = centerX + baseRadius * cos(rawAngle)
            val y = centerY + baseRadius * sin(rawAngle)
            val isActive = activeIndex >= 0 && index == activeIndex
            val absDistance = abs(distanceIndex)

            // Fade away from the focal axis. The 0.75 factor flattens the cosine so a line three
            // steps away is still readable; the linear term then removes the far tail entirely.
            val alpha = max(
                0.12f,
                cos(rawAngle * 0.75f).pow(2.5f) * (1f - (absDistance * 0.18f).toFloat()),
            )
            val scale = if (isActive) {
                tuning.activeScale
            } else {
                max(0.7f, 1f - (absDistance * 0.08f).toFloat())
            }

            items += PendoloLineItem(
                line = line,
                index = index,
                angleRad = rawAngle,
                angleDeg = rawAngle * (180f / PI.toFloat()),
                x = x,
                y = y,
                isActive = isActive,
                distanceFromActive = absDistance.toInt(),
                alpha = alpha,
                scale = scale,
            )
        }

        return items
    }
}
