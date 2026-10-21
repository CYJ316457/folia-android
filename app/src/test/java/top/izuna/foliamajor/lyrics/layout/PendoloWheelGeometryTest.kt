package top.izuna.foliamajor.lyrics.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.izuna.foliamajor.lyrics.Line
import top.izuna.foliamajor.lyrics.Word
import kotlin.math.PI
import kotlin.math.abs

/**
 * Tests for the wheel geometry.
 *
 * The bug class this guards against: the arc is a ring, so an off-by-one in the window or a sign
 * error in the angle puts lines on the wrong side of the hub. Neither shows up as a crash and both
 * look plausible in a screenshot of a single static frame.
 */
class PendoloWheelGeometryTest {

    private fun line(text: String, start: Double, end: Double) = Line(
        words = listOf(Word(text, start, end)),
        startTime = start,
        endTime = end,
        fullText = text,
    )

    private val lines = List(20) { index -> line("line $index", index.toDouble(), index + 1.0) }

    private fun layout(
        target: Double,
        heights: List<Float> = emptyList(),
        width: Float = 1080f,
        height: Float = 1920f,
    ) = PendoloWheelGeometry.layout(
        lines = lines,
        targetLineIndex = target,
        escapementAngleOffsetRad = 0f,
        viewportWidth = width,
        viewportHeight = height,
        tuning = PendoloTuning(),
        radiusOffsetPx = width * 0.06f,
        lineBlockHeightsPx = heights,
    )

    @Test
    fun `the focal line sits at angle zero on the right of the hub`() {
        val items = layout(target = 5.0)
        val focal = items.single { it.isActive }

        assertEquals(0f, focal.angleRad, 0.0001f)
        // Hub is at x = 0 with the default tuning, so the focal line is one radius to the right.
        assertEquals(focal.x, 1080f * 0.42f + 1080f * 0.06f, 0.01f)
        assertEquals(1920f * 0.5f, focal.y, 0.01f)
    }

    @Test
    fun `later lines curve downward and earlier lines upward`() {
        val items = layout(target = 5.0)

        val next = items.single { it.index == 6 }
        val previous = items.single { it.index == 4 }

        assertTrue("upcoming line should be below the focal axis", next.angleRad > 0f)
        assertTrue("past line should be above the focal axis", previous.angleRad < 0f)
    }

    @Test
    fun `angle grows with distance from the focal line`() {
        val items = layout(target = 5.0)
        val byIndex = items.associateBy { it.index }

        val oneStep = byIndex.getValue(6).angleRad
        val twoSteps = byIndex.getValue(7).angleRad

        assertTrue("two steps must be further round than one", twoSteps > oneStep)
        assertEquals(PendoloWheelGeometry.angleStepRad() * 2f, twoSteps, 0.0001f)
    }

    @Test
    fun `tall blocks push their neighbours further apart`() {
        // Whether the spacing term kicks in depends on whether the height term exceeds the plain
        // angle step, so the heights are chosen to be unambiguously large.
        val heights = List(lines.size) { 400f }

        val plain = layout(target = 5.0).associateBy { it.index }
        val spaced = layout(target = 5.0, heights = heights).associateBy { it.index }

        assertTrue(
            "a 400px-tall block must open a wider gap than the default step",
            spaced.getValue(6).angleRad > plain.getValue(6).angleRad + 0.01f,
        )
    }

    @Test
    fun `zero heights fall back to the plain angle step`() {
        val withZeroes = layout(target = 5.0, heights = List(lines.size) { 0f })
        val withNothing = layout(target = 5.0)

        assertEquals(
            withNothing.single { it.index == 6 }.angleRad,
            withZeroes.single { it.index == 6 }.angleRad,
            0.0001f,
        )
    }

    @Test
    fun `only the right semicircle is emitted`() {
        val items = layout(target = 5.0)

        assertTrue(
            "every emitted line must be within 90 degrees of the focal axis",
            items.all { abs(it.angleRad) < PI / 2 },
        )
    }

    @Test
    fun `a fractional target focuses no line`() {
        // `lastValid + 0.5` is what an instrumental gap produces: the wheel keeps ticking but no
        // line may claim to be current.
        val items = layout(target = 5.5)

        assertTrue("no item may be active for a fractional target", items.none { it.isActive })
        assertTrue("lines are still placed", items.isNotEmpty())
    }

    @Test
    fun `an out of range target still emits a window instead of crashing`() {
        val past = layout(target = lines.size.toDouble())
        val before = layout(target = -1.0)

        assertTrue("past the end still renders the tail", past.isNotEmpty())
        assertTrue("before the start still renders the head", before.isNotEmpty())
        assertTrue("nothing is active before playback starts", before.none { it.isActive })
    }

    @Test
    fun `transversing the window moves x along the circle`() {
        val items = layout(target = 5.0).sortedBy { it.index }
        val radius = items.map { item ->
            val cx = 0f
            val cy = 1920f * 0.5f
            kotlin.math.hypot(item.x - cx, item.y - cy)
        }

        // Every line sits on the same circle, so the distance from the hub is constant.
        val expected = radius.first()
        assertTrue("all lines share one radius", radius.all { abs(it - expected) < 0.5f })
    }

    @Test
    fun `alpha decays away from the focal axis but never reaches zero`() {
        val items = layout(target = 5.0)
        val focal = items.single { it.isActive }
        val neighbour = items.single { it.index == 6 }

        assertTrue("the focal line is the most opaque", focal.alpha >= neighbour.alpha)
        assertTrue("no line is fully transparent while it is emitted", items.all { it.alpha > 0f })
    }

    @Test
    fun `the active line is scaled up and neighbours are not`() {
        val items = layout(target = 5.0)

        assertEquals(PendoloTuning().activeScale, items.single { it.isActive }.scale, 0.0001f)
        assertTrue("neighbours stay at or below 1", items.filterNot { it.isActive }.all { it.scale <= 1f })
    }

    @Test
    fun `layout is a pure function of its inputs`() {
        val first = layout(target = 5.0)
        val second = layout(target = 5.0)

        assertEquals(first.map { it.index }, second.map { it.index })
        assertEquals(first.map { it.x }, second.map { it.x })
        assertEquals(first.map { it.angleRad }, second.map { it.angleRad })
    }

    @Test
    fun `an empty line list produces nothing`() {
        val items = PendoloWheelGeometry.layout(
            lines = emptyList(),
            targetLineIndex = 0.0,
            escapementAngleOffsetRad = 0f,
            viewportWidth = 1080f,
            viewportHeight = 1920f,
        )

        assertTrue(items.isEmpty())
    }

    @Test
    fun `the angle step follows the configured arc`() {
        // 100 degrees over 8 gaps.
        assertEquals((100.0 * PI / 180.0 / 8.0).toFloat(), PendoloWheelGeometry.angleStepRad(), 0.0001f)
        assertEquals(
            (60.0 * PI / 180.0 / 8.0).toFloat(),
            PendoloWheelGeometry.angleStepRad(PendoloTuning(arcAngleDeg = 60f)),
            0.0001f,
        )
    }

    @Test
    fun `positions are always on the circle of the requested radius`() {
        val items = layout(target = 8.0)
        val baseRadius = 1080f * 0.42f + 1080f * 0.06f
        val centerX = 1080f * PendoloTuning().wheelCenterX
        val centerY = 1920f * PendoloTuning().wheelCenterY

        items.forEach { item ->
            val distance = kotlin.math.hypot(item.x - centerX, item.y - centerY)
            assertEquals("line ${item.index} is off the circle", baseRadius, distance, 0.5f)
        }
    }
}
