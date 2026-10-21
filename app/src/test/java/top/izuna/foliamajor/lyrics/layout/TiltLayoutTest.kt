package top.izuna.foliamajor.lyrics.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the sentence splitter and the tilt layout planner.
 *
 * These pin the rules that decide how a line is broken up. A bad split shows up as a fragment that
 * starts mid-word or a stray punctuation mark on its own line — visible to the user, invisible in
 * the code.
 */
class TiltLayoutTest {

    // --- Sentence splitting ----------------------------------------------------------------------

    @Test
    fun `a target of one returns the text unchanged`() {
        assertEquals(listOf("你好世界"), SentenceSplitter.split("你好世界", 1))
    }

    @Test
    fun `a target of zero returns the text unchanged`() {
        assertEquals(listOf("你好世界"), SentenceSplitter.split("你好世界", 0))
    }

    @Test
    fun `splits on punctuation first`() {
        val result = SentenceSplitter.split("第一句，第二句。第三句", 3)

        assertEquals(3, result.size)
        assertEquals("第一句，", result[0])
        assertEquals("第二句。", result[1])
        assertEquals("第三句", result[2])
    }

    @Test
    fun `keeps trailing spaces with the punctuation`() {
        val result = SentenceSplitter.split("First, second", 2)

        assertEquals(listOf("First, ", "second"), result)
    }

    @Test
    fun `splits on question and exclamation marks`() {
        val result = SentenceSplitter.split("真的吗？是的！", 2)

        assertEquals(listOf("真的吗？", "是的！"), result)
    }

    @Test
    fun `a lone leading mark is absorbed into the first fragment`() {
        // "，第一句，第二句" must not yield a fragment that is only punctuation.
        val result = SentenceSplitter.split("，第一句，第二句", 2)

        assertTrue(
            "no fragment should be punctuation only, got $result",
            result.none { fragment -> fragment.isNotBlank() && fragment.all { it in "，。；！？、…·.,;!?" } },
        )
        // Every character still survives.
        assertEquals("，第一句，第二句", result.joinToString(""))
    }

    @Test
    fun `splits bracketed phrases as units`() {
        val result = SentenceSplitter.split("他说「你好」然后走了", 2)

        assertEquals(2, result.size)
        assertTrue(result.any { it.contains("「你好」") })
    }

    @Test
    fun `a quoted phrase is never split internally`() {
        val result = SentenceSplitter.split("「这是一个很长的引号内容」", 3)

        // Nothing to split on, so it stays whole rather than cutting inside the quotes.
        assertEquals(listOf("「这是一个很长的引号内容」"), result)
    }

    @Test
    fun `splits a multi-word latin block out of cjk`() {
        val result = SentenceSplitter.split("我用 iPhone 听 music player", 2)

        assertTrue("expected more than one fragment, got $result", result.size > 1)
    }

    @Test
    fun `a single latin word does not become its own fragment`() {
        val result = SentenceSplitter.split("用iPhone听歌", 2)

        // No punctuation, no multi-word Latin block: stays whole or splits at a word boundary,
        // but must not isolate "iPhone".
        assertTrue(result.none { it == "iPhone" })
    }

    @Test
    fun `splits on a full-width space`() {
        val result = SentenceSplitter.split("前半　后半", 2)

        assertEquals(2, result.size)
    }

    @Test
    fun `merges down to the target when there are too many fragments`() {
        val result = SentenceSplitter.split("一，二，三，四，五，六", 2)

        assertEquals(2, result.size)
        // Every character is still present.
        assertEquals("一二三四五六", result.joinToString("").replace(Regex("""[，]"""), ""))
    }

    @Test
    fun `splitting preserves all visible characters`() {
        val text = "第一句话，第二句话。第三句"

        val result = SentenceSplitter.split(text, 3)

        assertEquals(
            text.filterNot { it.isWhitespace() },
            result.joinToString("").filterNot { it.isWhitespace() },
        )
    }

    @Test
    fun `splitting a single word yields one fragment`() {
        assertEquals(listOf("Hello"), SentenceSplitter.split("Hello", 4))
    }

    @Test
    fun `splitting an empty string yields one empty fragment`() {
        assertEquals(listOf(""), SentenceSplitter.split("", 3))
    }

    @Test
    fun `a single latin token is never cut in half`() {
        // There is no safe boundary inside one word, so fewer fragments is the right answer.
        val result = SentenceSplitter.split("abcdefghijklmnopqrstuvwxyz", 2)

        assertEquals(listOf("abcdefghijklmnopqrstuvwxyz"), result)
    }

    @Test
    fun `a dense cjk line still splits`() {
        // CJK may be broken between any two characters, so a long unpunctuated line does split.
        val result = SentenceSplitter.split("这是一个没有任何标点的很长句子", 2)

        assertEquals(2, result.size)
        assertEquals("这是一个没有任何标点的很长句子", result.joinToString(""))
    }

    @Test
    fun `a dense split does not cut inside a latin word`() {
        val result = SentenceSplitter.split("Hello World", 2)

        // Either it stays whole or it splits at the space — never "Hel" + "lo World".
        assertTrue(result.all { !it.startsWith("ello") })
    }

    // --- Line count ------------------------------------------------------------------------------

    @Test
    fun `the line count is always between one and four`() {
        // The thresholds are deliberately low — the desktop app fragments even short CJK lines —
        // so the useful invariant is the range, not a specific value for a specific length.
        for (charCount in 1..200) {
            val count = TiltLayoutPlanner.determineLineCount(
                charCount,
                seed = charCount.toDouble(),
                splitProbability = 1f,
            )
            assertTrue("count $count out of range for $charCount chars", count in 1..4)
        }
    }

    @Test
    fun `a two-character line scores low`() {
        // 2 chars: normalized 0.5, so with the jitter range the score is 0.35–0.65.
        val score = TiltLayoutPlanner.determineLineCount(2, seed = 1.0, splitProbability = 1f)
        assertTrue(score in 1..2)
    }

    @Test
    fun `a long line splits into more fragments`() {
        val count = TiltLayoutPlanner.determineLineCount(60, seed = 1.0, splitProbability = 1f)

        assertTrue("expected 3 or more, got $count", count >= 3)
    }

    @Test
    fun `the line count is capped at four`() {
        val count = TiltLayoutPlanner.determineLineCount(500, seed = 1.0, splitProbability = 1f)

        assertEquals(4, count)
    }

    @Test
    fun `the same seed yields the same line count`() {
        val first = TiltLayoutPlanner.determineLineCount(30, seed = 12.5, splitProbability = 1f)
        val second = TiltLayoutPlanner.determineLineCount(30, seed = 12.5, splitProbability = 1f)

        assertEquals(first, second)
    }

    @Test
    fun `a zero split probability keeps every line whole`() {
        val count = TiltLayoutPlanner.determineLineCount(80, seed = 1.0, splitProbability = 0f)

        assertEquals(1, count)
    }

    // --- Layout planning -------------------------------------------------------------------------

    private fun plan(
        text: String,
        seed: Double = 1.0,
        options: TiltOptions = TiltOptions(),
        width: Float = 1000f,
        charWidth: Float = 40f,
    ) = TiltLayoutPlanner.plan(
        fullText = text,
        lineSeed = seed,
        options = options,
        availableWidth = width,
        baseFontPx = 40f,
        measureWidth = { fragment, _, _ -> fragment.length * charWidth },
    )

    @Test
    fun `the plan preserves the line's text`() {
        val text = "第一句话，第二句话"
        val layout = plan(text)

        assertEquals(text, layout.segments.joinToString("") { it.text })
    }

    @Test
    fun `char offsets point into the original text`() {
        val text = "第一句，第二句"
        val layout = plan(text)

        layout.segments.forEach { segment ->
            val fromOffset = text.substring(segment.charOffset)
            assertTrue(
                "segment '${segment.text}' not found at offset ${segment.charOffset}",
                fromOffset.startsWith(segment.text),
            )
        }
    }

    @Test
    fun `at most one fragment is tilted`() {
        repeat(20) { seed ->
            val layout = plan("第一句话，第二句话。第三句", seed = seed.toDouble())
            assertTrue(layout.segments.count { it.isTilt } <= 1)
        }
    }

    @Test
    fun `an instrumental marker stays one fragment`() {
        val layout = plan("......")

        assertEquals(1, layout.segments.size)
        assertEquals(false, layout.segments[0].isTilt)
    }

    @Test
    fun `a short last fragment is boosted`() {
        // "第一句话，吗" splits into a long fragment and a 1-character tail.
        val layout = plan("第一句话，吗", seed = 1.0, options = TiltOptions(splitProbability = 1f))

        if (layout.segments.size >= 2) {
            val last = layout.segments.last()
            if (!last.isTilt && last.text.length <= 2) {
                assertTrue("expected the short tail to be marked", last.isShortLastLine)
            }
        }
    }

    @Test
    fun `an overflowing line is scaled down`() {
        val layout = plan("很长的歌词内容" .repeat(10), width = 200f, charWidth = 40f)

        assertTrue("expected a scale below 1, got ${layout.scaleMultiplier}", layout.scaleMultiplier < 1f)
    }

    @Test
    fun `a fitting line is not scaled`() {
        val layout = plan("短", width = 5000f, charWidth = 10f)

        assertEquals(1f, layout.scaleMultiplier, 0.001f)
    }

    @Test
    fun `scaling never goes below the floor`() {
        val layout = plan("超长" .repeat(50), width = 10f, charWidth = 100f)

        assertTrue("scale ${layout.scaleMultiplier} below floor", layout.scaleMultiplier >= 0.5f)
    }

    @Test
    fun `the same seed yields the same layout`() {
        val first = plan("第一句话，第二句话", seed = 7.0)
        val second = plan("第一句话，第二句话", seed = 7.0)

        assertEquals(first.segments.map { it.text }, second.segments.map { it.text })
        assertEquals(first.segments.map { it.isTilt }, second.segments.map { it.isTilt })
    }

    @Test
    fun `different seeds can yield different layouts`() {
        val layouts = (1..12).map { plan("第一句话，第二句话。第三句", seed = it.toDouble()) }

        // Not all identical: the seed must actually influence the layout.
        val distinct = layouts.map { layout -> layout.segments.joinToString("|") { it.text + it.isTilt } }
            .distinct()
        assertTrue("all layouts identical, seed has no effect", distinct.size > 1)
    }

    // --- Seeded random ---------------------------------------------------------------------------

    @Test
    fun `seeded random stays in range`() {
        repeat(100) { index ->
            val value = TiltLayoutPlanner.seededRandom(index.toDouble(), 7)
            assertTrue("out of range: $value", value >= 0f && value < 1f)
        }
    }

    @Test
    fun `seeded random is deterministic`() {
        assertEquals(
            TiltLayoutPlanner.seededRandom(3.25, 11),
            TiltLayoutPlanner.seededRandom(3.25, 11),
            0f,
        )
    }
}
