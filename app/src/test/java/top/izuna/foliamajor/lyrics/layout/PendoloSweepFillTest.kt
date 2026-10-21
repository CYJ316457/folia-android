package top.izuna.foliamajor.lyrics.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.izuna.foliamajor.lyrics.Line
import top.izuna.foliamajor.lyrics.Word

/**
 * Tests for the lyric sweep fill.
 *
 * The sweep is what makes this mode legible as karaoke: the line is drawn dim and the sung part is
 * overdrawn in the accent colour, moving character by character. The pixel arithmetic here decides
 * whether that leading edge lands on the right glyph, and a wrap boundary is exactly where an
 * off-by-one hides.
 */
class PendoloSweepFillTest {

    private fun line(text: String, start: Double, end: Double, words: List<Word> = emptyList()) = Line(
        words = words,
        startTime = start,
        endTime = end,
        fullText = text,
    )

    // Three graphemes, 10px each: offsets are the cumulative boundaries.
    private val offsets = listOf(0f, 10f, 20f, 30f)
    private val timings = listOf(
        PendoloGraphemeTiming(1.0, 2.0),
        PendoloGraphemeTiming(2.0, 3.0),
        PendoloGraphemeTiming(3.0, 4.0),
    )

    @Test
    fun `nothing is filled before the first grapheme starts`() {
        val width = PendoloSweepFill.widthAt(timings, offsets, lineEndTime = 4.0, currentTime = 0.9, fullWidthPx = 30f)

        assertEquals(0f, width, 0.0001f)
    }

    @Test
    fun `the fill interpolates across a grapheme`() {
        // Halfway through the second grapheme should be 10 + 5.
        val width = PendoloSweepFill.widthAt(timings, offsets, lineEndTime = 4.0, currentTime = 2.5, fullWidthPx = 30f)

        assertEquals(15f, width, 0.0001f)
    }

    @Test
    fun `the fill reaches the start of the next grapheme exactly at its boundary`() {
        val width = PendoloSweepFill.widthAt(timings, offsets, lineEndTime = 4.0, currentTime = 2.0, fullWidthPx = 30f)

        // At t=2.0 the second grapheme has just begun, so the filled prefix is the first grapheme.
        assertEquals(10f, width, 0.0001f)
    }

    @Test
    fun `past the end the whole line is filled`() {
        val width = PendoloSweepFill.widthAt(timings, offsets, lineEndTime = 4.0, currentTime = 9.0, fullWidthPx = 30f)

        assertEquals(30f, width, 0.0001f)
    }

    @Test
    fun `without timings the line fills on its end time`() {
        assertEquals(
            0f,
            PendoloSweepFill.widthAt(emptyList(), offsets, lineEndTime = 4.0, currentTime = 3.9, fullWidthPx = 30f),
            0.0001f,
        )
        assertEquals(
            30f,
            PendoloSweepFill.widthAt(emptyList(), offsets, lineEndTime = 4.0, currentTime = 4.0, fullWidthPx = 30f),
            0.0001f,
        )
    }

    @Test
    fun `a zero duration grapheme does not divide by zero`() {
        val degenerate = listOf(PendoloGraphemeTiming(1.0, 1.0))

        val width = PendoloSweepFill.widthAt(degenerate, listOf(0f, 10f), 1.0, 1.0, 10f)

        assertTrue("must stay finite", width.isFinite())
    }

    @Test
    fun `visibility follows the first grapheme`() {
        assertTrue(PendoloSweepFill.visible(timings, 4.0, 1.5))
        assertTrue("at the exact start it is visible", PendoloSweepFill.visible(timings, 4.0, 1.0))
        assertTrue("before the start it is hidden", !PendoloSweepFill.visible(timings, 4.0, 0.5))
    }

    @Test
    fun `a line without timings is visible from its end time`() {
        assertTrue(!PendoloSweepFill.visible(emptyList(), 4.0, 3.0))
        assertTrue(PendoloSweepFill.visible(emptyList(), 4.0, 4.0))
    }

    @Test
    fun `even timings tile the span exactly`() {
        val built = PendoloSweepFill.evenTimings(4, 10.0, 14.0)

        assertEquals(4, built.size)
        assertEquals(10.0, built.first().startTime, 0.0001)
        assertEquals(14.0, built.last().endTime, 0.0001)
        // No gaps between neighbours.
        built.zipWithNext().forEach { (a, b) -> assertEquals(a.endTime, b.startTime, 0.0001) }
    }

    @Test
    fun `even timings of zero count are empty`() {
        assertTrue(PendoloSweepFill.evenTimings(0, 0.0, 1.0).isEmpty())
    }

    @Test
    fun `word timings drive per-grapheme timings`() {
        val target = line(
            text = "ab cd",
            start = 0.0,
            end = 2.0,
            words = listOf(Word("ab", 0.0, 1.0), Word("cd", 1.0, 2.0)),
        )

        val built = PendoloSweepFill.buildTimings(target)

        assertEquals("ab cd splits into five graphemes", 5, built.size)
        assertEquals(0.0, built[0].startTime, 0.0001)
        assertEquals(0.5, built[1].startTime, 0.0001)
        assertEquals("the space belongs to no word and takes the next word's start", 1.0, built[2].startTime, 0.0001)
        assertEquals(1.0, built[3].startTime, 0.0001)
    }

    @Test
    fun `the last grapheme ends at the line's end`() {
        val target = line(
            text = "abc",
            start = 0.0,
            end = 3.0,
            words = listOf(Word("abc", 0.0, 3.0)),
        )

        val built = PendoloSweepFill.buildTimings(target)

        assertEquals(3.0, built.last().endTime, 0.0001)
    }

    @Test
    fun `a line without words spreads evenly`() {
        val target = line("abcd", 0.0, 4.0)

        val built = PendoloSweepFill.buildTimings(target)

        assertEquals(4, built.size)
        assertEquals("the first grapheme starts with the line", 0.0, built[0].startTime, 0.0001)
        assertEquals(1.0, built[1].startTime, 0.0001)
        assertEquals(4.0, built.last().endTime, 0.0001)
    }

    @Test
    fun `words that do not tile the text do not shift later timings`() {
        // The provider dropped the space between the words: "abcd" with words "ab" and "cd" still
        // has to map both words onto the right graphemes.
        val target = line(
            text = "abcd",
            start = 0.0,
            end = 2.0,
            words = listOf(Word("ab", 0.0, 1.0), Word("cd", 1.0, 2.0)),
        )

        val built = PendoloSweepFill.buildTimings(target)

        assertEquals(4, built.size)
        assertEquals(0.0, built[0].startTime, 0.0001)
        assertEquals(1.0, built[2].startTime, 0.0001)
    }

    @Test
    fun `an empty line has no timings`() {
        assertTrue(PendoloSweepFill.buildTimings(line("", 0.0, 1.0)).isEmpty())
    }

    @Test
    fun `buildTimings output drives the fill`() {
        val target = line(
            text = "abc",
            start = 0.0,
            end = 3.0,
            words = listOf(Word("abc", 0.0, 3.0)),
        )
        val built = PendoloSweepFill.buildTimings(target)

        // Halfway through the second of three graphemes.
        val width = PendoloSweepFill.widthAt(built, listOf(0f, 10f, 20f, 30f), 3.0, 1.5, 30f)

        assertEquals(15f, width, 0.0001f)
    }

    @Test
    fun `an emoji stays one grapheme`() {
        val target = line("a👨‍👩‍👧b", 0.0, 3.0)

        val built = PendoloSweepFill.buildTimings(target)

        assertEquals("the ZWJ family sequence is one grapheme", 3, built.size)
    }

    @Test
    fun `timings are monotonic in start time`() {
        val target = line(
            text = "one two three",
            start = 0.0,
            end = 6.0,
            words = listOf(
                Word("one", 0.0, 2.0),
                Word("two", 2.0, 4.0),
                Word("three", 4.0, 6.0),
            ),
        )

        val built = PendoloSweepFill.buildTimings(target)
        val starts = built.map { it.startTime }

        assertTrue("starts must never go backwards, got $starts", starts.zipWithNext().all { (a, b) -> b >= a })
        assertNotEquals("the tiling must not collapse to a single instant", 0.0, starts.last(), 0.0001)
    }
}
