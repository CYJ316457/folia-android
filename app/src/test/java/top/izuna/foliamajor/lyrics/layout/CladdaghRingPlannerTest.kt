package top.izuna.foliamajor.lyrics.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests for the claddagh ring geometry.
 *
 * The two rules worth pinning are the ±90° rotation fold (which keeps the lower half of the ring
 * readable) and the arc-span cap (which stops a long line from wrapping around twice).
 */
class CladdaghRingPlannerTest {

    private fun plan(
        text: String,
        fontPx: Float = 24f,
        radius: Float = 400f,
    ) = CladdaghRingPlanner.plan(
        text = text,
        fontPx = fontPx,
        maxRadiusX = radius,
        measureWidth = { char, px -> char.length * px },
    )

    // --- Angle normalization ---------------------------------------------------------------------

    @Test
    fun `an angle inside the range is unchanged`() {
        assertEquals(45f, CladdaghRingPlanner.normalizeReadableAngle(45f), 0.001f)
    }

    @Test
    fun `an angle past 90 folds back`() {
        // 135° is the same visual orientation as -45°.
        assertEquals(-45f, CladdaghRingPlanner.normalizeReadableAngle(135f), 0.001f)
    }

    @Test
    fun `an angle below -90 folds forward`() {
        assertEquals(45f, CladdaghRingPlanner.normalizeReadableAngle(-135f), 0.001f)
    }

    @Test
    fun `a full turn returns the original orientation`() {
        assertEquals(30f, CladdaghRingPlanner.normalizeReadableAngle(390f), 0.001f)
    }

    @Test
    fun `every normalized angle is within 90 degrees`() {
        for (degrees in -720..720 step 7) {
            val normalized = CladdaghRingPlanner.normalizeReadableAngle(degrees.toFloat())
            assertTrue("$degrees -> $normalized out of range", abs(normalized) <= 90f)
        }
    }

    // --- Ring layout -----------------------------------------------------------------------------

    @Test
    fun `every visible character is placed`() {
        val layout = plan("晴天")

        assertEquals(listOf("晴", "天"), layout.chars.map { it.char })
    }

    @Test
    fun `spaces are dropped`() {
        val layout = plan("a b c")

        assertEquals(listOf("a", "b", "c"), layout.chars.map { it.char })
    }

    @Test
    fun `an empty line yields no characters`() {
        val layout = plan("")

        assertTrue(layout.chars.isEmpty())
    }

    @Test
    fun `progress runs from zero to one`() {
        val layout = plan("一二三四五")

        assertEquals(0f, layout.chars.first().progress, 0.001f)
        assertEquals(1f, layout.chars.last().progress, 0.001f)
    }

    @Test
    fun `a single character sits at the centre of the arc`() {
        val layout = plan("晴")

        assertEquals(0f, layout.chars[0].angle, 0.001f)
        assertEquals(0f, layout.chars[0].progress, 0.001f)
    }

    @Test
    fun `angles increase left to right`() {
        val layout = plan("一二三四五")

        layout.chars.zipWithNext().forEach { (left, right) ->
            assertTrue("angles must increase", right.angle > left.angle)
        }
    }

    @Test
    fun `the arc is centred on zero`() {
        val layout = plan("一二三四五")

        // The middle character's angle should be near zero, since the arc is centred.
        val middle = layout.chars[layout.chars.size / 2]
        assertTrue("middle angle ${middle.angle} should be near zero", abs(middle.angle) < 0.2f)
    }

    @Test
    fun `a long line is scaled to fit the arc cap`() {
        val shortSpan = plan("一二").chars.last().angle - plan("一二").chars.first().angle
        val longSpan = plan("一".repeat(200)).chars.last().angle -
            plan("一".repeat(200)).chars.first().angle

        // The cap is 4.25 rad; a 200-character line would span far more without scaling.
        assertTrue("long span $longSpan should be capped", longSpan <= 4.3f)
        assertTrue("long line spans more than a short one", longSpan > shortSpan)
    }

    @Test
    fun `the vertical radius is the 45-degree projection`() {
        val layout = plan("一二三", radius = 400f)

        assertEquals(400f * 0.707f, layout.radiusY, 1f)
    }

    @Test
    fun `the radius is at least twice the font size`() {
        // A tiny radius would fold the line onto itself.
        val layout = plan("一二三", fontPx = 100f, radius = 50f)

        assertTrue("radius ${layout.radiusX} too small", layout.radiusX >= 200f)
    }

    @Test
    fun `a two-character line spans a small arc`() {
        val layout = plan("晴天")

        val span = layout.chars.last().angle - layout.chars.first().angle
        assertTrue("span $span should be small", span < 1f)
    }

    // --- Position --------------------------------------------------------------------------------

    @Test
    fun `the first character of a centred arc sits near the top`() {
        val layout = plan("一二三")
        val middle = layout.chars[1]
        val (x, y) = CladdaghRingPlanner.positionOf(middle, layout.radiusX, layout.radiusY)

        // The arc is centred at -π/2, so the middle character is near (0, -radiusY).
        assertTrue("x $x should be near zero", abs(x) < layout.radiusX * 0.1f)
        assertTrue("y $y should be above the centre", y < 0f)
    }

    @Test
    fun `positions stay within the ellipse`() {
        val layout = plan("一二三四五六七八")

        layout.chars.forEach { char ->
            val (x, y) = CladdaghRingPlanner.positionOf(char, layout.radiusX, layout.radiusY)
            val normalized = (x / layout.radiusX) * (x / layout.radiusX) +
                (y / layout.radiusY) * (y / layout.radiusY)
            assertTrue("point off the ellipse: $normalized", abs(normalized - 1f) < 0.05f)
        }
    }
}
