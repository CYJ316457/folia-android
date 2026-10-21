package top.izuna.foliamajor.lyrics.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Tests for the clockwork display list and the colour arithmetic.
 *
 * The desktop's Canvas2D path is ~450 lines of drawing that cannot be unit-tested in a browser.
 * Splitting it into "decide what to draw" and "draw it" means the composition — layering order,
 * which elements appear at which decor level, whether the gear outline closes — is verifiable here.
 */
class PendoloClockworkBuilderTest {

    private val accent = PendoloColor.parse("#3B82F6")
    private val primary = PendoloColor.parse("#FFFFFF")

    private fun frame(
        decor: PendoloGearDecor = PendoloGearDecor.SUBTLE,
        gradient: Boolean = true,
        baseRadius: Float = 500f,
    ): PendoloClockworkFrame {
        val centerX = 40f
        val centerY = 900f
        val lyricRingRadius = baseRadius + 60f
        return PendoloClockworkFrame(
            centerX = centerX,
            centerY = centerY,
            baseRadius = baseRadius,
            escapementAngle = 0.4f,
            secondGearAngle = 0.2f,
            bassOscillation = 0.1f,
            phase = 1.2f,
            palette = PendoloClockworkScene.buildPalette(primary, accent, decor),
            anchors = PendoloClockworkScene.anchors(centerX, centerY, baseRadius, lyricRingRadius),
            accentArgb = accent,
            decor = decor,
            showCenterGradient = gradient,
        )
    }

    @Test
    fun `decor none draws nothing at all`() {
        val drawing = PendoloClockworkBuilder.build(frame(decor = PendoloGearDecor.NONE))

        assertTrue("no primitives", drawing.items.isEmpty())
        assertNull("no vignette either", drawing.gradient)
    }

    @Test
    fun `the vignette is emitted when requested`() {
        val drawing = PendoloClockworkBuilder.build(frame(gradient = true))

        assertNotNull(drawing.gradient)
        assertEquals(500f * 1.65f, drawing.gradient!!.radius, 0.01f)
    }

    @Test
    fun `the vignette fades to fully transparent at its edge`() {
        val drawing = PendoloClockworkBuilder.build(frame(gradient = true))

        assertEquals("centre is opaque enough to seat the mechanism", true, drawing.gradient!!.centerArgb ushr 24 > 100)
        assertEquals("edge alpha is zero", 0, drawing.gradient!!.outerArgb ushr 24)
    }

    @Test
    fun `the vignette can be turned off independently of the decor`() {
        val drawing = PendoloClockworkBuilder.build(frame(decor = PendoloGearDecor.FULL, gradient = false))

        assertNull(drawing.gradient)
        assertTrue("the mechanism still draws", drawing.items.isNotEmpty())
    }

    @Test
    fun `subtle is the same drawing at reduced opacity`() {
        val subtle = PendoloClockworkBuilder.build(frame(decor = PendoloGearDecor.SUBTLE))
        val full = PendoloClockworkBuilder.build(frame(decor = PendoloGearDecor.FULL))

        assertEquals("same number of primitives", full.items.size, subtle.items.size)

        val subtleAlpha = subtle.items.filterIsInstance<PendoloDrawItem.Ring>().first().value.strokeArgb ushr 24
        val fullAlpha = full.items.filterIsInstance<PendoloDrawItem.Ring>().first().value.strokeArgb ushr 24
        assertTrue("subtle must be lighter", subtleAlpha < fullAlpha)
        assertEquals("subtle is 60% of full", fullAlpha * 0.6f, subtleAlpha.toFloat(), 2f)
    }

    @Test
    fun `the guide rings come before the gears`() {
        val items = PendoloClockworkBuilder.build(frame()).items

        val firstGear = items.indexOfFirst { it is PendoloDrawItem.Gear }
        // The five concentric guide rings are the first rings in the list; later rings are the
        // bevel ring, rivets and bearing seats, which are deliberately drawn on top of the gears.
        val guideRings = items.withIndex()
            .filterIsInstance<IndexedValue<PendoloDrawItem.Ring>>()
            .take(5)
            .map { it.index }

        assertEquals("five guide rings", 5, guideRings.size)
        assertTrue("guide rings are the background", guideRings.all { it < firstGear })
    }

    @Test
    fun `every named component is present`() {
        val items = PendoloClockworkBuilder.build(frame(decor = PendoloGearDecor.FULL)).items

        val gears = items.filterIsInstance<PendoloDrawItem.Gear>()
        val wheels = items.filterIsInstance<PendoloDrawItem.Wheel>()
        val jewels = items.filterIsInstance<PendoloDrawItem.Jewel>()
        val springs = items.filterIsInstance<PendoloDrawItem.Hairspring>()
        val geneva = items.filterIsInstance<PendoloDrawItem.Geneva>()

        assertTrue("main wheel, hub, planets, balance, transmission, seconds, idler", gears.size >= 8)
        assertTrue("spoked faces", wheels.size >= 3)
        assertTrue("a jewel per bearing", jewels.size >= 6)
        assertEquals("exactly one hairspring", 1, springs.size)
        assertEquals("exactly one Geneva block", 1, geneva.size)
    }

    @Test
    fun `the focal axis ends before the lyric ring`() {
        val items = PendoloClockworkBuilder.build(frame()).items
        val arrow = items.filterIsInstance<PendoloDrawItem.Triangle>().single()
        val f = frame()

        val tipX = maxOf(arrow.value.ax, arrow.value.bx, arrow.value.cx)
        val lyricRingRadius = f.baseRadius + 60f
        assertTrue(
            "the arrow must not poke through the lyrics",
            tipX <= f.centerX + lyricRingRadius,
        )
    }

    @Test
    fun `the focal segment is horizontal`() {
        val items = PendoloClockworkBuilder.build(frame()).items
        val focal = items
            .filterIsInstance<PendoloDrawItem.Segment>()
            .last()
            .value

        assertEquals(focal.startY, focal.endY, 0.001f)
        assertTrue("it points right", focal.endX > focal.startX)
    }

    @Test
    fun `gear outlines close and alternate radius`() {
        val gear = PendoloGear(
            cx = 100f,
            cy = 100f,
            radius = 50f,
            teeth = 8,
            toothDepth = 10f,
            rotationRad = 0f,
            strokeArgb = accent,
            lineWidth = 2f,
        )

        val outline = PendoloClockworkBuilder.gearOutline(gear)

        assertEquals("four corners per tooth", 32, outline.size)
        val radii = outline.map { (x, y) -> hypot(x - gear.cx, y - gear.cy) }
        // Corners alternate inner, outer, outer, inner.
        assertEquals(40f, radii[0], 0.5f)
        assertEquals(50f, radii[1], 0.5f)
        assertEquals(50f, radii[2], 0.5f)
        assertEquals(40f, radii[3], 0.5f)
    }

    @Test
    fun `a gear with no teeth has no outline`() {
        val gear = PendoloGear(0f, 0f, 50f, 0, 10f, 0f, accent, 1f)

        assertTrue(PendoloClockworkBuilder.gearOutline(gear).isEmpty())
    }

    @Test
    fun `the hairspring spirals outward`() {
        val spring = PendoloHairspring(
            cx = 0f,
            cy = 0f,
            startRadius = 10f,
            endRadius = 40f,
            coils = 3.5f,
            oscillationRad = 0f,
            strokeArgb = accent,
            lineWidth = 1f,
        )

        val points = PendoloClockworkBuilder.hairspringPoints(spring)

        assertEquals("coils times 60 steps, plus the first point", 211, points.size)
        val firstRadius = hypot(points.first().first, points.first().second)
        val lastRadius = hypot(points.last().first, points.last().second)
        assertEquals(10f, firstRadius, 0.5f)
        assertEquals(40f, lastRadius, 0.5f)

        val radii = points.map { (x, y) -> hypot(x, y) }
        assertTrue("radius must never decrease", radii.zipWithNext().all { (a, b) -> b >= a - 0.001f })
    }

    @Test
    fun `the hairspring oscillation rotates the whole spiral`() {
        fun angleOf(oscillation: Float): Float {
            val points = PendoloClockworkBuilder.hairspringPoints(
                PendoloHairspring(0f, 0f, 10f, 40f, 1f, oscillation, accent, 1f),
            )
            return kotlin.math.atan2(points.first().second, points.first().first)
        }

        val zero = angleOf(0f)
        val quarter = angleOf(1.5707964f)

        assertEquals(0f, zero, 0.01f)
        assertEquals(1.5707964f, quarter, 0.01f)
    }

    @Test
    fun `colours parse hex forms`() {
        assertEquals(0xFFFFFFFF.toInt(), PendoloColor.parse("#FFFFFF"))
        assertEquals(0xFF112233.toInt(), PendoloColor.parse("#112233"))
        assertEquals(0xFFFF0000.toInt(), PendoloColor.parse("#F00"))
        assertEquals(0x80112233.toInt(), PendoloColor.parse("#80112233"))
    }

    @Test
    fun `unparseable colours fall back instead of throwing`() {
        assertEquals(0xFFFFFFFF.toInt(), PendoloColor.parse("rebeccapurple"))
        assertEquals(0xFF00FF00.toInt(), PendoloColor.parse("#nope", fallback = 0xFF00FF00.toInt()))
    }

    @Test
    fun `alpha replacement keeps the channels`() {
        val half = PendoloColor.withAlpha(0xFF336699.toInt(), 0.5f)

        assertEquals(0x336699, half and 0x00FFFFFF)
        assertEquals(127, half ushr 24)
    }

    @Test
    fun `alpha is clamped to the byte range`() {
        assertEquals(255, PendoloColor.withAlpha(0xFF000000.toInt(), 4f) ushr 24)
        assertEquals(0, PendoloColor.withAlpha(0xFF000000.toInt(), -1f) ushr 24)
    }
}
