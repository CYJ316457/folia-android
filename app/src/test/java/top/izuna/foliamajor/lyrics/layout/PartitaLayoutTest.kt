package top.izuna.foliamajor.lyrics.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.izuna.foliamajor.lyrics.Line
import top.izuna.foliamajor.lyrics.Word
import top.izuna.foliamajor.theme.DefaultTheme
import top.izuna.foliamajor.theme.Theme

/**
 * Tests for the partita column layout.
 *
 * The rules that matter: chunks must cover the whole line without losing or duplicating a word, and
 * the stagger must alternate so consecutive rows lean opposite ways.
 */
class PartitaLayoutTest {

    private fun line(text: String, words: List<Word>) = Line(
        words = words,
        startTime = words.firstOrNull()?.startTime ?: 0.0,
        endTime = words.lastOrNull()?.endTime ?: 1.0,
        fullText = text,
    )

    private fun lineOf(text: String, wordCount: Int): Line {
        val chars = text.filterNot { it.isWhitespace() }
        val chunk = chars.length / wordCount
        val words = (0 until wordCount).map { index ->
            val start = index * chunk
            val end = if (index == wordCount - 1) chars.length else (index + 1) * chunk
            Word(
                text = chars.substring(start, end),
                startTime = index.toDouble(),
                endTime = (index + 1).toDouble(),
            )
        }
        return line(chars, words)
    }

    // --- Row count -------------------------------------------------------------------------------

    @Test
    fun `two words always use one row`() {
        assertEquals(1, PartitaLayoutPlanner.targetColumnCount(30, 2))
    }

    @Test
    fun `five or fewer graphemes use one row`() {
        assertEquals(1, PartitaLayoutPlanner.targetColumnCount(5, 5))
    }

    @Test
    fun `ten graphemes use two rows`() {
        assertEquals(2, PartitaLayoutPlanner.targetColumnCount(10, 4))
    }

    @Test
    fun `sixteen graphemes use three rows`() {
        assertEquals(3, PartitaLayoutPlanner.targetColumnCount(16, 6))
    }

    @Test
    fun `twenty-four graphemes use four rows`() {
        assertEquals(4, PartitaLayoutPlanner.targetColumnCount(24, 8))
    }

    @Test
    fun `a very long line uses five rows`() {
        assertEquals(5, PartitaLayoutPlanner.targetColumnCount(40, 10))
    }

    // --- Plan ------------------------------------------------------------------------------------

    @Test
    fun `every word appears exactly once`() {
        val line = lineOf("一二三四五六七八九十", 5)

        val layout = PartitaLayoutPlanner.plan(line, DefaultTheme, 600f, PartitaOptions())

        val words = layout.chunks.flatMap { chunk -> chunk.words.map { it.text } }
        assertEquals(line.words.map { it.text }, words)
    }

    @Test
    fun `an empty line yields no chunks`() {
        val layout = PartitaLayoutPlanner.plan(
            line("", emptyList()),
            DefaultTheme,
            600f,
            PartitaOptions(),
        )

        assertTrue(layout.chunks.isEmpty())
    }

    @Test
    fun `a line with one word yields one chunk`() {
        val layout = PartitaLayoutPlanner.plan(
            lineOf("晴天", 1),
            DefaultTheme,
            600f,
            PartitaOptions(),
        )

        assertEquals(1, layout.chunks.size)
    }

    @Test
    fun `stagger alternates between rows`() {
        val line = lineOf("一二三四五六七八九十十一十二", 6)

        val layout = PartitaLayoutPlanner.plan(line, DefaultTheme, 600f, PartitaOptions())
        if (layout.chunks.size >= 2) {
            val first = layout.chunks[0].x
            val second = layout.chunks[1].x
            assertTrue("row 0 should lean left of row 1", first < second)
        }
    }

    @Test
    fun `stagger stays within the configured range`() {
        val line = lineOf("一二三四五六七八九十十一十二", 6)
        val options = PartitaOptions(staggerMin = 10f, staggerMax = 40f)

        val layout = PartitaLayoutPlanner.plan(line, DefaultTheme, 600f, options)

        layout.chunks.forEach { chunk ->
            val magnitude = kotlin.math.abs(chunk.x)
            assertTrue("stagger $magnitude outside 10..40", magnitude in 10f..40.01f)
        }
    }

    @Test
    fun `the same line always composes identically`() {
        val line = lineOf("一二三四五六七八九十", 5)

        val first = PartitaLayoutPlanner.plan(line, DefaultTheme, 600f, PartitaOptions())
        val second = PartitaLayoutPlanner.plan(line, DefaultTheme, 600f, PartitaOptions())

        assertEquals(first.chunks.map { it.x }, second.chunks.map { it.x })
        assertEquals(first.chunks.map { it.scale }, second.chunks.map { it.scale })
    }

    @Test
    fun `calm themes apply no stagger offset`() {
        val calm = DefaultTheme.copy(animationIntensity = Theme.AnimationIntensity.CALM)
        val line = lineOf("一二三四五六七八九十", 5)

        val layout = PartitaLayoutPlanner.plan(line, calm, 600f, PartitaOptions())

        // Calm keeps the horizontal stagger (that is the mode's identity) but drops the vertical
        // drift and rotation.
        layout.chunks.forEach { chunk ->
            assertEquals(0f, chunk.y, 0.001f)
            assertEquals(0f, chunk.rotate, 0.001f)
        }
    }

    @Test
    fun `chaotic themes add rotation`() {
        val chaotic = DefaultTheme.copy(animationIntensity = Theme.AnimationIntensity.CHAOTIC)
        val line = lineOf("一二三四五六七八九十", 5)

        val layout = PartitaLayoutPlanner.plan(line, chaotic, 600f, PartitaOptions())

        assertTrue("expected some rotation", layout.chunks.any { it.rotate != 0f })
    }

    @Test
    fun `a short canvas limits the row count`() {
        val line = lineOf("一二三四五六七八九十十一十二十三十四十五十六", 10)

        // 150px of height fits one row of 100px after the 0.65 factor.
        val layout = PartitaLayoutPlanner.plan(line, DefaultTheme, 150f, PartitaOptions())

        assertEquals(1, layout.chunks.size)
    }

    // --- Active chunk ----------------------------------------------------------------------------

    @Test
    fun `nothing is active before the line starts`() {
        val layout = PartitaLayoutPlanner.plan(
            lineOf("一二三四五六", 3),
            DefaultTheme,
            600f,
            PartitaOptions(),
        )

        assertEquals(-1, PartitaLayoutPlanner.activeChunkIndex(layout, -1.0))
    }

    @Test
    fun `the first chunk is active once its word starts`() {
        val layout = PartitaLayoutPlanner.plan(
            lineOf("一二三四五六", 3),
            DefaultTheme,
            600f,
            PartitaOptions(),
        )

        assertEquals(0, PartitaLayoutPlanner.activeChunkIndex(layout, 0.5))
    }

    @Test
    fun `the last chunk stays active to the end`() {
        val layout = PartitaLayoutPlanner.plan(
            lineOf("一二三四五六", 3),
            DefaultTheme,
            600f,
            PartitaOptions(),
        )

        val lastIndex = layout.chunks.lastIndex
        assertEquals(lastIndex, PartitaLayoutPlanner.activeChunkIndex(layout, 999.0))
    }
}
