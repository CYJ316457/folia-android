package top.izuna.foliamajor.lyrics.layout

import top.izuna.foliamajor.lyrics.Line
import kotlin.math.abs
import kotlin.math.min

/**
 * Which line the wheel should focus on, and how strongly a line on the arc is shown.
 *
 * Ported from `pendoloTimeline.ts` and [PendoloWheelGeometry]'s companion opacity rule.
 */
object PendoloTimeline {

    /** Half-width of the arc where lines are fully opaque, in degrees. */
    const val VISIBLE_ARC_DEG = 110f

    /** Width of the fade band at the arc's edges, in degrees. */
    const val EDGE_FADE_DEG = 28f

    /**
     * The focal index when playback is not inside a timed line.
     *
     * Three cases the caller has to distinguish, and the desktop distinguishes them because each
     * looks wrong if handled as another:
     *
     *  - Before the first line: nothing is current, and the wheel should sit at the top. `-1` means
     *    "no focus", which [PendoloWheelGeometry.layout] turns into a window centred on line 0.
     *  - After the last line: return `lines.size`, i.e. one past the end. The wheel then rotates
     *    until every line has scrolled above the focal axis, which is the "song has ended" shot.
     *    Returning the last index instead would park the final line in the focal position forever.
     *  - In an instrumental gap: return `lastValid + 0.5`. The half step lands the camera between
     *    two lines, so the free-running wheel keeps ticking without any line looking current.
     *
     * [hasObservedLine] guards the gap case: at the very start, before playback has ever been
     * inside a line, `lastValidLineIndex` is still its initial value and a half-step from it would
     * focus a line the song has not reached.
     */
    fun fallbackAnchorIndex(
        lines: List<Line>,
        currentLineIndex: Int,
        lastValidLineIndex: Int,
        hasObservedLine: Boolean,
        currentTime: Double,
    ): Double {
        if (currentLineIndex >= 0 && currentLineIndex < lines.size) {
            return currentLineIndex.toDouble()
        }

        if (!hasObservedLine) return -1.0

        val finalLine = lines.lastOrNull()
        val finalRenderEndTime = finalLine?.renderEndTime
        if (finalRenderEndTime != null && currentTime > finalRenderEndTime) {
            return lines.size.toDouble()
        }

        return lastValidLineIndex + 0.5
    }

    /**
     * Opacity for a line at [baseAngleDeg] while the wheel is rotated by [wheelRotationDeg].
     *
     * The desktop hides a line past ±110° of *unwrapped* angle rather than by index distance, so a
     * line disappears exactly when it leaves the arc instead of when some counter reaches a limit.
     * Fading over the last 28° avoids the pop a hard cutoff would produce as the wheel ticks.
     */
    fun rotatingLineOpacity(
        baseAngleDeg: Float,
        wheelRotationDeg: Float,
        baseOpacity: Float,
    ): Float {
        val visibleAngleDeg = abs(baseAngleDeg + wheelRotationDeg)
        if (visibleAngleDeg >= VISIBLE_ARC_DEG) return 0f
        val edgeProgress = min(1f, (VISIBLE_ARC_DEG - visibleAngleDeg) / EDGE_FADE_DEG)
        return baseOpacity * edgeProgress
    }
}
