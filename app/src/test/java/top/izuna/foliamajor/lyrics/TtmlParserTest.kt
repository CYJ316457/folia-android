package top.izuna.foliamajor.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TTML parser tests.
 *
 * The fixtures are cut down from real Apple Music / AMLL documents. What they pin is the part that
 * is easy to get wrong and impossible to see by reading the parser: time-attribute correction,
 * whitespace becoming `endsWithSpace`, and the Western-syllable merge that keeps "Hello" from being
 * laid out as two boxes.
 */
class TtmlParserTest {

    private fun ttml(body: String, timing: String = "Word", head: String = "") = """
        <?xml version="1.0" encoding="utf-8"?>
        <tt xmlns="http://www.w3.org/ns/ttml"
            xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
            xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
            itunes:timing="$timing">
          <head>$head</head>
          <body>
            <div>
              $body
            </div>
          </body>
        </tt>
    """.trimIndent()

    // --- Time grammar ----------------------------------------------------------------------------

    @Test
    fun `parses mm ss milliseconds`() {
        assertEquals(62030L, parseTtmlTime("01:02.030"))
    }

    @Test
    fun `parses hh mm ss`() {
        assertEquals(3723000L, parseTtmlTime("01:02:03.000"))
    }

    @Test
    fun `parses seconds with a trailing s`() {
        assertEquals(12500L, parseTtmlTime("12.5s"))
    }

    @Test
    fun `parses a bare number as seconds`() {
        assertEquals(3000L, parseTtmlTime("3"))
    }

    @Test
    fun `unparseable times become zero instead of throwing`() {
        assertEquals(0L, parseTtmlTime("not-a-time"))
        assertEquals(0L, parseTtmlTime(null))
        assertEquals(0L, parseTtmlTime(""))
    }

    // --- Basic line parsing ----------------------------------------------------------------------

    @Test
    fun `parses a line-timed document`() {
        val result = parseTTML(
            ttml("""<p begin="1s" end="3s"><span>Hello world</span></p>""", timing = "Line"),
            includeInterludes = false,
        )

        assertEquals(1, result.lines.size)
        val line = result.lines[0]
        assertEquals("Hello world", line.fullText)
        assertEquals(1.0, line.startTime, 0.001)
        assertEquals(3.0, line.endTime, 0.001)
        assertFalse(result.isWordByWord)
        // A line-timed line still gets words, evenly spread, so the renderer has something to draw.
        assertTrue(line.words.isNotEmpty())
    }

    @Test
    fun `parses word-timed syllables`() {
        val result = parseTTML(
            ttml(
                """
                <p begin="0s" end="2s">
                  <span begin="0s" end="1s">你</span><span begin="1s" end="2s">好</span>
                </p>
                """.trimIndent()
            ),
            includeInterludes = false,
        )

        assertTrue(result.isWordByWord)
        val words = result.lines[0].words
        assertEquals(listOf("你", "好"), words.map { it.text })
        assertEquals(0.0, words[0].startTime, 0.001)
        assertEquals(1.0, words[1].startTime, 0.001)
    }

    @Test
    fun `merges western syllables into one word`() {
        // TTML splits "Hello" into "Hel" + "lo"; the renderer lays out words, so they must merge.
        val result = parseTTML(
            ttml(
                """
                <p begin="0s" end="2s">
                  <span begin="0s" end="1s">Hel</span><span begin="1s" end="2s">lo</span>
                </p>
                """.trimIndent()
            ),
            includeInterludes = false,
        )

        val words = result.lines[0].words
        assertEquals(1, words.size)
        assertEquals("Hello", words[0].text)
    }

    @Test
    fun `does not merge across a space`() {
        // A trailing space sets endsWithSpace, which blocks the merge — otherwise "ab cd" becomes one box.
        val result = parseTTML(
            ttml(
                """
                <p begin="0s" end="3s">
                  <span begin="0s" end="1s">ab </span><span begin="1s" end="2s">cd</span>
                </p>
                """.trimIndent()
            ),
            includeInterludes = false,
        )

        assertEquals(listOf("ab", "cd"), result.lines[0].words.map { it.text })
    }

    @Test
    fun `does not merge cjk syllables`() {
        val result = parseTTML(
            ttml(
                """
                <p begin="0s" end="2s">
                  <span begin="0s" end="1s">春</span><span begin="1s" end="2s">天</span>
                </p>
                """.trimIndent()
            ),
            includeInterludes = false,
        )

        assertEquals(listOf("春", "天"), result.lines[0].words.map { it.text })
    }

    // --- Time correction -------------------------------------------------------------------------

    @Test
    fun `children supply a missing line time`() {
        // Apple Music word-timed lines often declare no begin/end at all.
        val result = parseTTML(
            ttml("""<p><span begin="2s" end="4s">Hi</span></p>"""),
            includeInterludes = false,
        )

        assertEquals(2.0, result.lines[0].startTime, 0.001)
        assertEquals(4.0, result.lines[0].endTime, 0.001)
    }

    @Test
    fun `an earlier child pulls the line start back`() {
        // A declared start later than the first syllable would cut the word off.
        val result = parseTTML(
            ttml("""<p begin="5s" end="8s"><span begin="3s" end="8s">Hi</span></p>"""),
            includeInterludes = false,
        )

        assertEquals(3.0, result.lines[0].startTime, 0.001)
    }

    @Test
    fun `a later child extends the line end`() {
        val result = parseTTML(
            ttml("""<p begin="1s" end="2s"><span begin="1s" end="6s">Hi</span></p>"""),
            includeInterludes = false,
        )

        assertEquals(6.0, result.lines[0].endTime, 0.001)
    }

    // --- Metadata --------------------------------------------------------------------------------

    @Test
    fun `reads the line key and agent`() {
        val result = parseTTML(
            ttml(
                """
                <p begin="0s" end="2s" itunes:key="L1" ttm:agent="v1">
                  <span begin="0s" end="2s">Hi</span>
                </p>
                """.trimIndent(),
                head = """<metadata><iTunesMetadata/></metadata>""",
            ),
            includeInterludes = false,
        )

        assertEquals("L1", result.lines[0].id)
        assertEquals("v1", result.lines[0].agentId)
    }

    @Test
    fun `marks a chorus song part`() {
        val doc = """
            <tt xmlns="http://www.w3.org/ns/ttml"
                xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
                itunes:timing="Line">
              <body>
                <div itunes:songPart="Chorus">
                  <p begin="0s" end="2s"><span>Hi</span></p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val result = parseTTML(doc, includeInterludes = false)

        assertTrue(result.lines[0].isChorus)
        assertEquals("Chorus", result.lines[0].songPart)
    }

    // --- Translations ----------------------------------------------------------------------------

    @Test
    fun `reads an inline translation span`() {
        val result = parseTTML(
            ttml(
                """
                <p begin="0s" end="2s">
                  <span begin="0s" end="2s">Hello</span>
                  <span ttm:role="x-translation" xml:lang="zh-Hans">你好</span>
                </p>
                """.trimIndent()
            ),
            includeInterludes = false,
        )

        val line = result.lines[0]
        assertEquals("Hello", line.fullText)
        assertEquals("你好", line.translation)
    }

    @Test
    fun `reads an inline romanization span`() {
        val result = parseTTML(
            ttml(
                """
                <p begin="0s" end="2s">
                  <span begin="0s" end="2s">こんにちは</span>
                  <span ttm:role="x-roman" xml:lang="ja-Latn">konnichiwa</span>
                </p>
                """.trimIndent()
            ),
            includeInterludes = false,
        )

        assertEquals("konnichiwa", result.lines[0].romanization)
        // The romanization text must not leak into the sung line.
        assertEquals("こんにちは", result.lines[0].fullText)
    }

    // --- Duets -----------------------------------------------------------------------------------

    @Test
    fun `folds an overlapping duet line into the host`() {
        // Two overlapping lines with different agents are simultaneous vocals, not two lines.
        val result = parseTTML(
            ttml(
                """
                <p begin="0s" end="4s" ttm:agent="v1"><span begin="0s" end="4s">Main</span></p>
                <p begin="2s" end="4s" ttm:agent="v2"><span begin="2s" end="4s">Harmony</span></p>
                """.trimIndent()
            ),
            includeInterludes = false,
        )

        assertEquals(1, result.lines.size)
        assertEquals("Main", result.lines[0].fullText)
    }

    @Test
    fun `keeps sequential lines by different agents`() {
        val result = parseTTML(
            ttml(
                """
                <p begin="0s" end="2s" ttm:agent="v1"><span begin="0s" end="2s">First</span></p>
                <p begin="3s" end="5s" ttm:agent="v2"><span begin="3s" end="5s">Second</span></p>
                """.trimIndent()
            ),
            includeInterludes = false,
        )

        assertEquals(2, result.lines.size)
        assertEquals(listOf("First", "Second"), result.lines.map { it.fullText })
    }

    @Test
    fun `keeps overlapping lines that share an agent`() {
        // Same agent means the same singer; overlapping is then a data artefact, not a duet.
        val result = parseTTML(
            ttml(
                """
                <p begin="0s" end="4s" ttm:agent="v1"><span begin="0s" end="4s">A</span></p>
                <p begin="2s" end="4s" ttm:agent="v1"><span begin="2s" end="4s">B</span></p>
                """.trimIndent()
            ),
            includeInterludes = false,
        )

        assertEquals(2, result.lines.size)
    }

    // --- Background vocals -----------------------------------------------------------------------

    @Test
    fun `strips parentheses from a background vocal`() {
        val result = parseTTML(
            ttml(
                """
                <p begin="0s" end="4s">
                  <span begin="0s" end="4s">Main</span>
                  <span ttm:role="x-bg" begin="1s" end="3s">(oh yeah)</span>
                </p>
                """.trimIndent()
            ),
            includeInterludes = false,
        )

        // The background vocal must not be appended to the sung line.
        assertEquals("Main", result.lines[0].fullText)
    }

    // --- Robustness ------------------------------------------------------------------------------

    @Test
    fun `malformed xml yields no lines instead of throwing`() {
        val result = parseTTML("<tt><body><div><p>unclosed")

        assertTrue(result.lines.isEmpty())
    }

    @Test
    fun `a document with no body yields no lines`() {
        val result = parseTTML("""<tt xmlns="http://www.w3.org/ns/ttml"><head/></tt>""")

        assertTrue(result.lines.isEmpty())
    }

    @Test
    fun `lines are sorted by start time`() {
        val result = parseTTML(
            ttml(
                """
                <p begin="5s" end="7s"><span>Second</span></p>
                <p begin="1s" end="3s"><span>First</span></p>
                """.trimIndent(),
                timing = "Line",
            ),
            includeInterludes = false,
        )

        assertEquals(listOf("First", "Second"), result.lines.map { it.fullText })
    }

    @Test
    fun `strips a utf8 bom`() {
        val result = parseTTML(
            "\uFEFF" + ttml("""<p begin="1s" end="2s"><span>Hi</span></p>""", timing = "Line"),
            includeInterludes = false,
        )

        assertEquals(1, result.lines.size)
    }

    @Test
    fun `attaches render hints`() {
        val result = parseTTML(
            ttml("""<p begin="1s" end="4s"><span>Hi</span></p>""", timing = "Line"),
            includeInterludes = false,
        )

        assertNotNull(result.lines[0].renderHints)
    }

    @Test
    fun `dispatches ttml through parseLyricsByFormat`() {
        val doc = ttml("""<p begin="1s" end="2s"><span>Hi</span></p>""", timing = "Line")

        val result = parseLyricsByFormat(LyricParseFormat.TTML, doc, includeInterludes = false)

        assertEquals(1, result.lines.size)
        assertEquals("Hi", result.lines[0].fullText)
    }
}
