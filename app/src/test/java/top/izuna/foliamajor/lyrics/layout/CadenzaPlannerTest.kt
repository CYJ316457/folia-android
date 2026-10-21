package top.izuna.foliamajor.lyrics.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.izuna.foliamajor.lyrics.Line
import top.izuna.foliamajor.lyrics.Word
import top.izuna.foliamajor.theme.DefaultTheme
import top.izuna.foliamajor.theme.Theme
import kotlin.math.abs

/**
 * Tests for the cadenza placement planner.
 *
 * The property that matters is **no overlap**: every word is a free-floating object, and two words
 * on top of each other are unreadable. The search may fail to find a free spot, but it must never
 * silently stack words when a free one exists.
 */
class CadenzaPlannerTest {

    private fun lineOf(parts: List<String>): Line {
        var time = 0.0
        val words = parts.map { text ->
            val word = Word(text = text, startTime = time, endTime = time + 0.5)
            time += 0.5
            word
        }
        return Line(
            words = words,
            startTime = 0.0,
            endTime = time,
            fullText = parts.joinToString(""),
        )
    }

    private fun plan(parts: List<String>, theme: Theme = DefaultTheme) =
        CadenzaPlanner.plan(lineOf(parts), theme, CadenzaOptions())

    // --- Basic shape -----------------------------------------------------------------------------

    @Test
    fun `every word is placed`() {
        val layout = plan(listOf("第一句", "歌词", "在这里"))

        assertEquals(3, layout.placements.size)
    }

    @Test
    fun `an empty line yields nothing`() {
        val layout = plan(emptyList())

        assertTrue(layout.placements.isEmpty())
        assertEquals(-1, layout.heroWordIndex)
    }

    @Test
    fun `a single word is its own hero`() {
        val layout = plan(listOf("晴天"))

        assertEquals(0, layout.heroWordIndex)
        assertTrue(layout.placements.single().isHero)
    }

    @Test
    fun `exactly one word is the hero`() {
        val layout = plan(listOf("一", "二", "三", "四", "五"))

        assertEquals(1, layout.placements.count { it.isHero })
    }

    @Test
    fun `the hero is enlarged`() {
        val layout = plan(listOf("一", "二", "三"))
        val hero = layout.placements.first { it.isHero }
        val other = layout.placements.first { !it.isHero }

        assertTrue("hero scale ${hero.scale} should exceed ${other.scale}", hero.scale > other.scale)
    }

    // --- No overlap ------------------------------------------------------------------------------

    @Test
    fun `no two words overlap`() {
        val layout = plan(listOf("一二三", "四五六", "七八九", "十一", "十二"))

        // Each placement's own bounding box, sized generously: the planner inflates its collision
        // boxes, so a smaller check is a strictly weaker claim.
        val boxes = layout.placements.map { placement ->
            val halfWidth = placement.text.length * 34f * placement.scale * 0.5f
            val halfHeight = 46f * placement.scale * 0.5f
            Rect(
                placement.x - halfWidth,
                placement.y - halfHeight,
                placement.x + halfWidth,
                placement.y + halfHeight,
            )
        }

        boxes.forEachIndexed { i, a ->
            boxes.drop(i + 1).forEachIndexed { offset, b ->
                val j = i + 1 + offset
                val overlap = a.overlapArea(b)
                assertTrue(
                    "words $i (${layout.placements[i].text}) and $j (${layout.placements[j].text}) overlap by $overlap",
                    overlap <= 1f,
                )
            }
        }
    }

    @Test
    fun `a long line does not stack words`() {
        val parts = (1..12).map { "词$it" }
        val layout = plan(parts)

        // Even when the field is crowded, distinct positions must be produced.
        val distinct = layout.placements.map { (it.x.toInt() to it.y.toInt()) }.distinct()
        assertTrue("expected mostly distinct positions, got ${distinct.size}", distinct.size >= parts.size - 2)
    }

    // --- Determinism -----------------------------------------------------------------------------

    @Test
    fun `the same line always places words identically`() {
        val first = plan(listOf("一", "二", "三"))
        val second = plan(listOf("一", "二", "三"))

        assertEquals(first.placements.map { it.x }, second.placements.map { it.x })
        assertEquals(first.placements.map { it.y }, second.placements.map { it.y })
    }

    // --- Intensity -------------------------------------------------------------------------------

    @Test
    fun `calm themes apply no rotation`() {
        val calm = DefaultTheme.copy(animationIntensity = Theme.AnimationIntensity.CALM)

        val layout = plan(listOf("一", "二", "三"), calm)

        layout.placements.forEach { assertEquals(0f, it.rotationDeg, 0.001f) }
    }

    @Test
    fun `normal themes apply some rotation away from the centre`() {
        val layout = plan(listOf("一", "二", "三四五六七八"))

        assertTrue("expected rotation", layout.placements.any { abs(it.rotationDeg) > 0.5f })
    }

    @Test
    fun `chaotic themes rotate more than normal`() {
        val chaotic = DefaultTheme.copy(animationIntensity = Theme.AnimationIntensity.CHAOTIC)
        val parts = listOf("一", "二", "三四五六七八")

        val normal = plan(parts)
        val wild = plan(parts, chaotic)

        fun totalRotation(layout: CadenzaLayout) = layout.placements.sumOf { abs(it.rotationDeg).toDouble() }

        assertTrue(
            "chaotic ${totalRotation(wild)} should exceed normal ${totalRotation(normal)}",
            totalRotation(wild) > totalRotation(normal),
        )
    }

    // --- Drift -----------------------------------------------------------------------------------

    @Test
    fun `drift points away from the centre`() {
        val layout = plan(listOf("一", "二", "三", "四"))

        layout.placements.forEach { placement ->
            if (abs(placement.x) < 1f && abs(placement.y) < 1f) return@forEach

            // The dot product of position and drift must be non-negative: drift never pulls a word
            // back toward the middle, which would undo the dispersal.
            val dot = placement.x * placement.passedDriftX + placement.y * placement.passedDriftY
            assertTrue("drift toward centre for '${placement.text}': $dot", dot >= -0.01f)
        }
    }

    // --- Active word -----------------------------------------------------------------------------

    @Test
    fun `nothing is active before the line starts`() {
        val layout = plan(listOf("一", "二", "三"))

        assertEquals(-1, CadenzaPlanner.activeWordIndex(layout, -1.0))
    }

    @Test
    fun `the first word is active at the start`() {
        val layout = plan(listOf("一", "二", "三"))

        assertEquals(0, CadenzaPlanner.activeWordIndex(layout, 0.1))
    }

    @Test
    fun `the last word stays active to the end`() {
        val layout = plan(listOf("一", "二", "三"))

        assertEquals(2, CadenzaPlanner.activeWordIndex(layout, 999.0))
    }

    // --- Hero selection --------------------------------------------------------------------------

    @Test
    fun `a cjk word is preferred over a short latin one`() {
        val layout = plan(listOf("a", "晴天", "b"))

        assertEquals("晴天", layout.placements[layout.heroWordIndex].text)
    }

    @Test
    fun `a centre word gets a bias over an equally weighted edge word`() {
        // Three identical one-character words: the middle one wins on the centre bias.
        val layout = plan(listOf("一", "二", "三"))

        assertEquals(1, layout.heroWordIndex)
    }

    private data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun overlapArea(other: Rect): Float {
            val width = minOf(right, other.right) - maxOf(left, other.left)
            val height = minOf(bottom, other.bottom) - maxOf(top, other.top)
            return if (width > 0 && height > 0) width * height else 0f
        }
    }
}
