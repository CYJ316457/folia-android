package top.izuna.foliamajor.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests.
 *
 * These pin the behaviours that are easy to break while porting and impossible to notice by reading
 * the code: KRC's relative offsets, QRC's leading/trailing text split, awlrc's verbatim-millisecond
 * fractions, and the one-to-one translation pairing.
 *
 * Most tests pass `includeInterludes = false`. Interlude insertion is a separate post-processing step
 * with its own tests at the end; leaving it on would put a "......" line at index 0 of any fixture
 * whose first line starts after 3s, so every index-based assertion would be testing the decorator
 * instead of the parser.
 */
class LyricParserTest {

    // --- LRC -------------------------------------------------------------------------------------

    @Test
    fun `parses simple lrc with two-digit centiseconds`() {
        val result = parseLRC("[00:12.50]First line\n[00:15.00]Second line", includeInterludes = false)

        assertEquals(2, result.lines.size)
        assertEquals("First line", result.lines[0].fullText)
        assertEquals(12.5, result.lines[0].startTime, 0.001)
        assertEquals(15.0, result.lines[1].startTime, 0.001)
        assertFalse(result.isWordByWord)
    }

    @Test
    fun `parses three-digit millisecond fraction`() {
        val result = parseLRC("[01:02.345]Line", includeInterludes = false)

        assertEquals(62.345, result.lines[0].startTime, 0.001)
    }

    @Test
    fun `caps a long line's duration to its estimated reading time`() {
        // A 30s gap before the next line should not hold a 5-character line on screen for 30s.
        val result = parseLRC("[00:00.00]short\n[00:30.00]next", includeInterludes = false)

        val duration = result.lines[0].endTime - result.lines[0].startTime
        assertTrue("duration was $duration", duration < 30.0)
    }

    @Test
    fun `uses five seconds for the last line`() {
        val result = parseLRC("[00:00.00]only line", includeInterludes = false)

        assertEquals(5.0, result.lines[0].endTime - result.lines[0].startTime, 0.001)
    }

    @Test
    fun `plain lrc does not read ti and ar metadata`() {
        // Matches the desktop parser: only enhanced LRC and awlrc read the metadata tags, so
        // parseLRC must drop them rather than report a title the desktop build would not show.
        val result = parseLRC("[ti:Song Title]\n[ar:The Artist]\n[00:01.00]Line", includeInterludes = false)

        assertNull(result.title)
        assertNull(result.artist)
        assertEquals(1, result.lines.size)
    }

    @Test
    fun `sorts out-of-order lines`() {
        val result = parseLRC("[00:10.00]Second\n[00:05.00]First", includeInterludes = false)

        assertEquals("First", result.lines[0].fullText)
        assertEquals("Second", result.lines[1].fullText)
    }

    @Test
    fun `strips a utf8 bom`() {
        val result = parseLRC("\uFEFF[00:01.00]Line", includeInterludes = false)

        assertEquals("Line", result.lines[0].fullText)
    }

    @Test
    fun `splits cjk per character and ignores punctuation weight`() {
        val result = parseLRC("[00:00.00]你好，世界", includeInterludes = false)
        val words = result.lines[0].words

        // Every character becomes a word, so the renderer can highlight per syllable.
        assertEquals(listOf("你", "好", "，", "世", "界"), words.map { it.text })
        // The comma carries no singing time, so it cannot start after the character that follows it.
        assertTrue(words[3].startTime >= words[2].startTime)
    }

    // --- Translation pairing ---------------------------------------------------------------------

    @Test
    fun `pairs translations one to one`() {
        val main = "[00:01.00]Line A\n[00:02.00]Line B"
        val trans = "[00:01.00]译文 A\n[00:02.00]译文 B"

        val result = parseLRC(main, trans, includeInterludes = false)

        assertEquals("译文 A", result.lines[0].translation)
        assertEquals("译文 B", result.lines[1].translation)
    }

    @Test
    fun `does not let one translation serve two adjacent lines`() {
        // The desktop bug this guards: with nearest-neighbour matching, a translation within 1s of
        // two lines is handed to both, so the second line shows the first line's translation.
        val main = "[00:01.00]Line A\n[00:01.50]Line B"
        val trans = "[00:01.00]Only one"

        val result = parseLRC(main, trans, includeInterludes = false)

        assertEquals("Only one", result.lines[0].translation)
        assertNull(result.lines[1].translation)
    }

    @Test
    fun `ignores a translation further than one second away`() {
        val result = parseLRC("[00:01.00]Line", "[00:09.00]Far away", includeInterludes = false)

        assertNull(result.lines[0].translation)
    }

    // --- Enhanced LRC ----------------------------------------------------------------------------

    @Test
    fun `parses inline angle word timings`() {
        val result = parseEnhancedLRC("[00:00.00]<00:00.00>Hello <00:00.50>world", includeInterludes = false)

        assertTrue(result.isWordByWord)
        val words = result.lines[0].words
        assertEquals(listOf("Hello ", "world"), words.map { it.text })
        assertEquals(0.0, words[0].startTime, 0.001)
        assertEquals(0.5, words[1].startTime, 0.001)
    }

    @Test
    fun `keeps plain lines alongside word-timed ones`() {
        // The word-timed line needs two angle markers: the desktop parser only builds a precise draft
        // when there are at least two, since a single marker gives no word duration.
        val result = parseEnhancedLRC(
            "[00:00.00]<00:00.00>Timed <00:00.50>line\n[00:05.00]Plain",
            includeInterludes = false,
        )

        assertEquals(2, result.lines.size)
        assertTrue(result.isWordByWord)
        assertEquals(listOf("Timed ", "line"), result.lines[0].words.map { it.text })
        // The plain line still gets placeholder words so the renderer has something to draw.
        assertTrue(result.lines[1].words.isNotEmpty())
        assertEquals("Plain", result.lines[1].fullText)
    }

    @Test
    fun `a single inline angle tag still yields a usable line`() {
        // With one marker there is no word duration to derive, so the line falls back to the plain
        // path. That path only strips leading [..] tags (parseSimpleTimedTextEntry in parserCore.ts),
        // so a lone <> tag stays glued to the text. The line must still not be dropped — even though
        // it keeps the tag, matching the desktop parser exactly.
        val result = parseEnhancedLRC("[00:00.00]<00:00.00>Only one tag", includeInterludes = false)

        assertEquals(1, result.lines.size)
        assertEquals("<00:00.00>Only one tag", result.lines[0].fullText)
        assertTrue(result.lines[0].words.isNotEmpty())
    }

    @Test
    fun `collapses repeated line tags into one line at the last tag`() {
        // "[t1][t2]text" looks like two entries, but buildPreciseLineDraft (parserCore.ts) attaches the
        // trailing text to the marker it follows, so two line-only tags collapse into a single draft
        // whose text hangs off the LAST tag. This pins the desktop behaviour rather than an idealised
        // one: the faithful port keeps the same single line starting at 20s.
        val result = parseEnhancedLRC("[00:10.00][00:20.00]Repeated", includeInterludes = false)

        assertEquals(1, result.lines.size)
        assertEquals("Repeated", result.lines[0].fullText)
        assertEquals(20.0, result.lines[0].startTime, 0.001)
    }

    @Test
    fun `enhanced lrc reads ti and ar metadata`() {
        val result = parseEnhancedLRC(
            "[ti:Song Title]\n[ar:The Artist]\n[00:00.00]<00:00.00>Hi",
            includeInterludes = false,
        )

        assertEquals("Song Title", result.title)
        assertEquals("The Artist", result.artist)
    }

    // --- VTT -------------------------------------------------------------------------------------

    @Test
    fun `parses vtt cues`() {
        val vtt = """
            WEBVTT

            00:00:01.000 --> 00:00:03.500
            First cue

            00:00:04.000 --> 00:00:06.000
            Second cue
        """.trimIndent()

        val result = parseVTT(vtt, includeInterludes = false)

        assertEquals(2, result.lines.size)
        assertEquals("First cue", result.lines[0].fullText)
        assertEquals(1.0, result.lines[0].startTime, 0.001)
        assertEquals(3.5, result.lines[0].endTime, 0.001)
    }

    @Test
    fun `strips vtt markup and entities`() {
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n<v Speaker>Hello &amp; goodbye</v>"

        val result = parseVTT(vtt, includeInterludes = false)

        assertEquals("Hello & goodbye", result.lines[0].fullText)
    }

    @Test
    fun `skips vtt note blocks`() {
        val vtt = "WEBVTT\n\nNOTE this is a comment\n\n00:00:01.000 --> 00:00:02.000\nReal cue"

        val result = parseVTT(vtt, includeInterludes = false)

        assertEquals(1, result.lines.size)
        assertEquals("Real cue", result.lines[0].fullText)
    }

    // --- YRC -------------------------------------------------------------------------------------

    @Test
    fun `parses yrc with absolute millisecond word offsets`() {
        val yrc = "[1000,2000](1000,500,0)Hello (1500,500,0)world"

        val result = parseYRC(yrc, includeInterludes = false)

        assertTrue(result.isWordByWord)
        val line = result.lines[0]
        assertEquals(1.0, line.startTime, 0.001)
        assertEquals(3.0, line.endTime, 0.001)
        assertEquals(listOf("Hello ", "world"), line.words.map { it.text })
        assertEquals(1.0, line.words[0].startTime, 0.001)
        assertEquals(1.5, line.words[1].startTime, 0.001)
    }

    // --- QRC -------------------------------------------------------------------------------------

    @Test
    fun `parses qrc with trailing text after each tag`() {
        val qrc = "[1000,2000](1000,500)Hello (1500,500)world"

        val result = parseQRC(qrc, includeInterludes = false)

        assertTrue(result.isWordByWord)
        assertEquals(listOf("Hello ", "world"), result.lines[0].words.map { it.text })
    }

    @Test
    fun `parses qrc with leading text before each tag`() {
        // The producer put the text BEFORE the tag; the line-wide shape decides the reading.
        val qrc = "[1000,2000]Hello (1000,500)world (1500,500)"

        val result = parseQRC(qrc, includeInterludes = false)

        assertEquals(listOf("Hello ", "world "), result.lines[0].words.map { it.text })
    }

    @Test
    fun `tolerates qrc tags with a third field`() {
        val qrc = "[1000,2000](1000,500,0)Hello"

        val result = parseQRC(qrc, includeInterludes = false)

        assertEquals("Hello", result.lines[0].fullText)
    }

    // --- KRC -------------------------------------------------------------------------------------

    @Test
    fun `krc word offsets are relative to the line start`() {
        // This is the single easiest thing to get wrong: KRC offsets are relative, unlike QRC/YRC.
        val krc = "[1000,2000]<0,500,0>Hello <500,500,0>world"

        val result = parseKRC(krc, includeInterludes = false)

        val words = result.lines[0].words
        assertEquals(1.0, words[0].startTime, 0.001)
        assertEquals(1.5, words[1].startTime, 0.001)
        assertEquals(2.0, words[1].endTime, 0.001)
    }

    @Test
    fun `decodes krc embedded translation and romanization tracks`() {
        // {"content":[{"type":0,"lyricContent":[["ro"]]},{"type":1,"lyricContent":[["trans"]]}]}
        val payload = """{"content":[{"type":0,"lyricContent":[["ro"]]},{"type":1,"lyricContent":[["trans"]]}]}"""
        val encoded = java.util.Base64.getEncoder().encodeToString(payload.toByteArray())
        val krc = "[language:$encoded]\n[1000,2000]<0,500,0>Hello"

        val result = parseKRC(krc, includeInterludes = false)

        assertEquals("trans", result.lines[0].translation)
        assertEquals("ro", result.lines[0].romanization)
    }

    @Test
    fun `falls back to even word timing when krc has no word tags`() {
        val krc = "[1000,2000]Plain text"

        val result = parseKRC(krc, includeInterludes = false)

        assertEquals("Plain text", result.lines[0].fullText)
        assertTrue(result.lines[0].words.isNotEmpty())
    }

    // --- AWLRC -----------------------------------------------------------------------------------

    @Test
    fun `awlrc fraction is a verbatim millisecond count`() {
        // [00:43.97] is 43s + 97ms, NOT 43s + 970ms. Reading it as centiseconds shifts the line.
        val result = parseAwlrc("[00:43.97]<0,100>Hi", includeInterludes = false)

        assertEquals(43.097, result.lines[0].startTime, 0.001)
    }

    @Test
    fun `awlrc supports hours field`() {
        val result = parseAwlrc("[01:02:03.500]<0,100>Hi", includeInterludes = false)

        assertEquals(3723.5, result.lines[0].startTime, 0.001)
    }

    @Test
    fun `awlrc repairs a backwards syllable marker`() {
        // KuGou occasionally emits a marker that goes backwards; the cursor clamps it forward.
        val result = parseAwlrc("[00:10.00]<0,1000>A<500,1000>B", includeInterludes = false)

        val words = result.lines[0].words
        assertEquals(10.0, words[0].startTime, 0.001)
        assertTrue("second word must not start before the first ends", words[1].startTime >= 11.0)
    }

    @Test
    fun `awlrc clamps the derived line end to the next line`() {
        // A padded final syllable would otherwise overrun into the next line's start.
        val result = parseAwlrc("[00:10.00]<0,5000>A\n[00:12.00]<0,500>B", includeInterludes = false)

        assertTrue(result.lines[0].endTime <= 12.0)
    }

    // --- Interludes and render hints -------------------------------------------------------------

    @Test
    fun `inserts an interlude for a long gap`() {
        val result = parseLRC("[00:01.00]First\n[00:30.00]Second")

        val interludes = result.lines.filter { it.fullText == INTERLUDE_FULL_TEXT }
        assertTrue("expected an interlude line", interludes.isNotEmpty())
    }

    @Test
    fun `inserts a lead-in interlude before a late first line`() {
        val result = parseLRC("[00:10.00]First")

        assertEquals(INTERLUDE_FULL_TEXT, result.lines[0].fullText)
        assertEquals("First", result.lines[1].fullText)
    }

    @Test
    fun `interlude words are spread across the gap`() {
        val result = parseLRC("[00:01.00]First\n[00:30.00]Second")
        val interlude = result.lines.first { it.fullText == INTERLUDE_FULL_TEXT }

        assertEquals(6, interlude.words.size)
        // Each dot gets an equal slice, so the interlude animates instead of sitting static.
        val firstDuration = interlude.words[0].duration
        interlude.words.forEach { word ->
            assertEquals(firstDuration, word.duration, 0.001)
        }
    }

    @Test
    fun `can disable interludes`() {
        val result = parseLRC("[00:01.00]First\n[00:30.00]Second", includeInterludes = false)

        assertTrue(result.lines.none { it.fullText == INTERLUDE_FULL_TEXT })
    }

    @Test
    fun `attaches render hints to every line`() {
        val result = parseLRC("[00:01.00]First\n[00:05.00]Second", includeInterludes = false)

        result.lines.forEach { line ->
            assertNotNull("line '${line.fullText}' has no render hints", line.renderHints)
        }
    }

    @Test
    fun `classifies a very short line as micro`() {
        val hints = buildLineRenderHints(
            Line(
                words = emptyList(),
                startTime = 0.0,
                endTime = 0.05,
                fullText = "x",
            )
        )

        assertEquals(LineTimingClass.MICRO, hints.timingClass)
        assertEquals(LineTransitionMode.NONE, hints.lineTransitionMode)
        assertEquals(WordRevealMode.INSTANT, hints.wordRevealMode)
    }

    @Test
    fun `classifies a short line as fast`() {
        val hints = buildLineRenderHints(
            Line(words = emptyList(), startTime = 0.0, endTime = 0.15, fullText = "x")
        )

        assertEquals(LineTimingClass.SHORT, hints.timingClass)
        assertEquals(LineTransitionMode.FAST, hints.lineTransitionMode)
        assertEquals(WordRevealMode.FAST, hints.wordRevealMode)
    }

    @Test
    fun `classifies a normal line as normal`() {
        val hints = buildLineRenderHints(
            Line(words = emptyList(), startTime = 0.0, endTime = 3.0, fullText = "x")
        )

        assertEquals(LineTimingClass.NORMAL, hints.timingClass)
        assertEquals(LineTransitionMode.NORMAL, hints.lineTransitionMode)
        assertEquals(WordRevealMode.NORMAL, hints.wordRevealMode)
    }

    @Test
    fun `render end time never precedes the line end`() {
        val line = Line(
            words = listOf(Word("a", 1.0, 3.0)),
            startTime = 1.0,
            endTime = 3.0,
            fullText = "a",
        )
        val hints = buildLineRenderHints(line)

        assertTrue(hints.renderEndTime >= line.endTime)
    }

    // --- Format detection ------------------------------------------------------------------------

    @Test
    fun `detects vtt from its header`() {
        assertEquals(LyricParseFormat.VTT, LyricParseFormat.detect("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi"))
    }

    @Test
    fun `detects vtt from a timing line without a header`() {
        assertEquals(
            LyricParseFormat.VTT,
            LyricParseFormat.detect("00:00:01.000 --> 00:00:02.000\nHi"),
        )
    }

    @Test
    fun `detects enhanced lrc from angle timings`() {
        assertEquals(LyricParseFormat.ENHANCED_LRC, LyricParseFormat.detect("[00:00.00]<00:00.00>Hi"))
    }

    @Test
    fun `detects plain lrc by default`() {
        assertEquals(LyricParseFormat.LRC, LyricParseFormat.detect("[00:01.00]Hi"))
    }

    @Test
    fun `detects format from a file extension`() {
        assertEquals(LyricParseFormat.KRC, LyricParseFormat.fromFileName("song.krc"))
        assertEquals(LyricParseFormat.QRC, LyricParseFormat.fromFileName("song.QRC"))
        assertNull(LyricParseFormat.fromFileName("song.mp3"))
    }

    // --- Degenerate input ------------------------------------------------------------------------

    @Test
    fun `parses an empty lyric body to no lines`() {
        val result = parseLRC("")

        assertTrue(result.lines.isEmpty())
    }

    @Test
    fun `skips lines with a timestamp but no text`() {
        val result = parseLRC("[00:01.00]\n[00:02.00]Real", includeInterludes = false)

        assertEquals(1, result.lines.size)
        assertEquals("Real", result.lines[0].fullText)
    }

    @Test
    fun `ignores non-timestamp garbage`() {
        val result = parseLRC("just some text\n[00:02.00]Real", includeInterludes = false)

        assertEquals(1, result.lines.size)
    }
}
