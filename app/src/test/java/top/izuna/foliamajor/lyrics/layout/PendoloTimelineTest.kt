package top.izuna.foliamajor.lyrics.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.izuna.foliamajor.lyrics.Line

/**
 * Tests for the wheel anchor resolution and the arc-edge fade.
 *
 * These two functions decide what the mode looks like when playback is *not* inside a line, which is
 * most of a real song (intros, instrumental breaks, the tail). Getting them wrong produces the two
 * classic failures: the wheel freezes on the last line forever, or the wheel spins past the end and
 * shows an empty arc.
 */
class PendoloTimelineTest {

    private fun line(start: Double, end: Double) = Line(
        words = emptyList(),
        startTime = start,
        endTime = end,
        fullText = "line",
    )

    private val lines = List(5) { index -> line(index.toDouble(), index + 1.0) }

    @Test
    fun `an active line index wins over everything else`() {
        val anchor = PendoloTimeline.fallbackAnchorIndex(
            lines = lines,
            currentLineIndex = 2,
            lastValidLineIndex = 1,
            hasObservedLine = true,
            currentTime = 2.5,
        )

        assertEquals(2.0, anchor, 0.0)
    }

    @Test
    fun `before any line is observed there is no focus`() {
        val anchor = PendoloTimeline.fallbackAnchorIndex(
            lines = lines,
            currentLineIndex = -1,
            lastValidLineIndex = 0,
            hasObservedLine = false,
            currentTime = 0.0,
        )

        assertEquals(-1.0, anchor, 0.0)
    }

    @Test
    fun `an instrumental gap parks between two lines`() {
        // Halfway keeps the wheel ticking without any line looking current.
        val anchor = PendoloTimeline.fallbackAnchorIndex(
            lines = lines,
            currentLineIndex = -1,
            lastValidLineIndex = 3,
            hasObservedLine = true,
            currentTime = 4.2,
        )

        assertEquals(3.5, anchor, 0.0)
    }

    @Test
    fun `past the final line the anchor moves one past the end`() {
        val anchor = PendoloTimeline.fallbackAnchorIndex(
            lines = lines,
            currentLineIndex = -1,
            lastValidLineIndex = 4,
            hasObservedLine = true,
            currentTime = 99.0,
        )

        assertEquals(
            "returning the last index would park the final line in focus forever",
            lines.size.toDouble(),
            anchor,
            0.0,
        )
    }

    @Test
    fun `just before the final line ends the anchor is still the gap form`() {
        val anchor = PendoloTimeline.fallbackAnchorIndex(
            lines = lines,
            currentLineIndex = -1,
            lastValidLineIndex = 4,
            hasObservedLine = true,
            currentTime = 4.5,
        )

        assertEquals(4.5, anchor, 0.0)
    }

    @Test
    fun `an empty line list does not crash`() {
        val anchor = PendoloTimeline.fallbackAnchorIndex(
            lines = emptyList(),
            currentLineIndex = -1,
            lastValidLineIndex = 0,
            hasObservedLine = true,
            currentTime = 10.0,
        )

        // No final line means no final render end, so this falls through to the gap form.
        assertEquals(0.5, anchor, 0.0)
    }

    @Test
    fun `a line on the focal axis is fully opaque`() {
        val opacity = PendoloTimeline.rotatingLineOpacity(
            baseAngleDeg = 0f,
            wheelRotationDeg = 0f,
            baseOpacity = 0.8f,
        )

        assertEquals(0.8f, opacity, 0.0001f)
    }

    @Test
    fun `a line past the visible arc is hidden`() {
        val opacity = PendoloTimeline.rotatingLineOpacity(
            baseAngleDeg = 0f,
            wheelRotationDeg = 120f,
            baseOpacity = 0.8f,
        )

        assertEquals(0f, opacity, 0f)
    }

    @Test
    fun `the fade band is gradual, not a hard cutoff`() {
        // The band starts at 110 - 28 = 82 degrees, so 70 is still fully lit and 96 is inside it.
        val inside = PendoloTimeline.rotatingLineOpacity(0f, 70f, 1f)
        val edge = PendoloTimeline.rotatingLineOpacity(0f, 96f, 1f)
        val outside = PendoloTimeline.rotatingLineOpacity(0f, 109f, 1f)

        assertTrue("inside the band the line is fully shown", inside > 0.9f)
        assertTrue("inside the fade band it is partly faded", edge in 0.1f..0.9f)
        assertTrue("near the edge it is almost gone", outside < 0.2f)
    }

    @Test
    fun `opacity decreases monotonically as the line rotates out`() {
        val samples = (0..110 step 10).map { angle ->
            PendoloTimeline.rotatingLineOpacity(0f, angle.toFloat(), 1f)
        }

        val decreasing = samples.zipWithNext().all { (a, b) -> b <= a + 0.0001f }
        assertTrue("fade must be monotonic, got $samples", decreasing)
    }

    @Test
    fun `negative rotation fades symmetrically`() {
        val positive = PendoloTimeline.rotatingLineOpacity(0f, 60f, 1f)
        val negative = PendoloTimeline.rotatingLineOpacity(0f, -60f, 1f)

        assertEquals(positive, negative, 0.0001f)
    }

    @Test
    fun `a line's own base angle moves it out of view without rotation`() {
        val visible = PendoloTimeline.rotatingLineOpacity(baseAngleDeg = 20f, wheelRotationDeg = 0f, baseOpacity = 1f)
        val hidden = PendoloTimeline.rotatingLineOpacity(baseAngleDeg = 120f, wheelRotationDeg = 0f, baseOpacity = 1f)

        assertTrue(visible > 0f)
        assertFalse(hidden > 0f)
    }
}
