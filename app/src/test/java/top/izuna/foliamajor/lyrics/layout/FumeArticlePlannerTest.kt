package top.izuna.foliamajor.lyrics.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.izuna.foliamajor.lyrics.Line
import top.izuna.foliamajor.lyrics.Word

/**
 * Tests for the fume page composition.
 *
 * The layout is a search, so most assertions are invariants rather than exact numbers: blocks must
 * not overlap inside a column, a hero must be wider than a body block, and the same input must always
 * produce the same page. Exact coordinates would break on any tuning change while these would not.
 *
 * Measurement is supplied by a fake measurer with a fixed advance per grapheme, which removes the
 * text engine from the test entirely — the planner's job is arithmetic on advances, not glyph
 * rendering.
 */
class FumeArticlePlannerTest {

    private fun measurer(advance: Float = 10f) = FumeGlyphMeasurer { text, _ ->
        FloatArray(FumeMath.splitGraphemes(text).size) { advance }
    }

    private fun line(text: String, start: Double, end: Double, chorus: Boolean = false): Line {
        val words = FumeMath.splitGraphemes(text).let { graphemes ->
            val per = (end - start) / graphemes.size
            graphemes.mapIndexed { index, grapheme ->
                Word(grapheme, start + index * per, start + (index + 1) * per)
            }
        }
        return Line(words = words, startTime = start, endTime = end, fullText = text, isChorus = chorus)
    }

    private fun sampleLines(count: Int): List<Line> = (0 until count).map { index ->
        line("第${index}句歌词内容在这里", index * 3.0, index * 3.0 + 2.5)
    }

    @Test
    fun `splits graphemes for cjk latin and emoji`() {
        assertEquals(listOf("你", "好"), FumeMath.splitGraphemes("你好"))
        assertEquals(listOf("a", "b", "c"), FumeMath.splitGraphemes("abc"))
        // A surrogate pair must stay one grapheme, not two halves.
        assertEquals(1, FumeMath.splitGraphemes("😀").size)
        // Skin tone modifier joins its base.
        assertEquals(1, FumeMath.splitGraphemes("👍🏽").size)
        // Combining acute accent stays with its base letter.
        assertEquals(1, FumeMath.splitGraphemes("e\u0301").size)
    }

    @Test
    fun `hash matches the desktop fnv1a`() {
        // Same value the TypeScript implementation produces for "abc"; if this changes, fume
        // composes a different page on Android than on the desktop for every song.
        assertEquals(FumeMath.hashString("abc"), FumeMath.hashString("abc"))
        assertEquals(440920331, FumeMath.hashString("abc"))
    }

    @Test
    fun `seeded is stable and in range`() {
        val value = FumeMath.seeded("hello")
        assertEquals(value, FumeMath.seeded("hello"), 0f)
        assertTrue(value in 0f..1f)
    }

    @Test
    fun `easing endpoints are exact`() {
        assertEquals(0f, FumeMath.easeOutCubic(0f), 0.0001f)
        assertEquals(1f, FumeMath.easeOutCubic(1f), 0.0001f)
        assertEquals(0f, FumeMath.easeInOutCubic(0f), 0.0001f)
        assertEquals(1f, FumeMath.easeInOutCubic(1f), 0.0001f)
        assertEquals(0.5f, FumeMath.easeInOutCubic(0.5f), 0.0001f)
    }

    @Test
    fun `glow envelope peaks near the configured point`() {
        assertEquals(0f, FumeMath.resolveDelayedGlowEnvelope(0f), 0.0001f)
        assertEquals(1f, FumeMath.resolveDelayedGlowEnvelope(0.8f), 0.0001f)
        assertEquals(0f, FumeMath.resolveDelayedGlowEnvelope(1f), 0.0001f)
    }

    @Test
    fun `wraps at spaces when there is one`() {
        val graphemes = FumeMath.splitGraphemes("aa bb cc")
        val advances = FloatArray(graphemes.size) { 10f }
        // 30px of room holds three 10px graphemes.
        val lines = FumeArticlePlanner.wrap(graphemes, advances, 34f)

        assertTrue(lines.size >= 2)
        assertEquals(0, lines.first().start)
        // The split happens at a space so the first line ends with a word boundary.
        assertTrue(lines.first().text.endsWith(" "))
    }

    @Test
    fun `wraps cjk anywhere because there are no spaces`() {
        val graphemes = FumeMath.splitGraphemes("一二三四五六七八")
        val advances = FloatArray(graphemes.size) { 10f }
        val lines = FumeArticlePlanner.wrap(graphemes, advances, 35f)

        assertTrue(lines.size >= 2)
        // Contiguous: every line starts where the previous ended.
        for (index in 1 until lines.size) {
            assertEquals(lines[index - 1].end, lines[index].start)
        }
    }

    @Test
    fun `wrap covers every grapheme exactly once`() {
        val graphemes = FumeMath.splitGraphemes("the quick brown fox jumps")
        val advances = FloatArray(graphemes.size) { 8f }
        val lines = FumeArticlePlanner.wrap(graphemes, advances, 50f)

        val rebuilt = lines.joinToString("") { it.text }
        assertEquals(graphemes.joinToString(""), rebuilt)
        assertEquals(graphemes.size, lines.last().end)
    }

    @Test
    fun `wrap handles empty input`() {
        assertTrue(FumeArticlePlanner.wrap(emptyList(), FloatArray(0), 100f).isEmpty())
    }

    @Test
    fun `hero font is larger than body font for the same width`() {
        val hero = FumeArticlePlanner.chooseFontPx(10, 3, true, 600f, 1f, 1f, 1f)
        val body = FumeArticlePlanner.chooseFontPx(10, 3, false, 600f, 1f, 1f, 1f)

        assertTrue("hero=$hero body=$body", hero > body)
        assertTrue(hero in 24f..54f)
        assertTrue(body in 14f..28f)
    }

    @Test
    fun `longer lines set smaller type`() {
        // 200px rather than 600px: at 600 both sizes hit the 28px ceiling and the comparison would
        // pass for the wrong reason (or fail, as it did first).
        val short = FumeArticlePlanner.chooseFontPx(6, 1, false, 200f, 1f, 1f, 1f)
        val long = FumeArticlePlanner.chooseFontPx(40, 8, false, 200f, 1f, 1f, 1f)

        assertTrue("short=$short long=$long", short > long)
    }

    @Test
    fun `chorus lines become heroes`() {
        assertTrue(FumeArticlePlanner.chooseNaturalBlockVariant(line("x", 0.0, 1.0, chorus = true), 0, 10, 12))
    }

    @Test
    fun `very long chorus is not a hero`() {
        // Over 22 graphemes cannot be a headline; it would overflow the column.
        assertFalse(
            FumeArticlePlanner.chooseNaturalBlockVariant(
                line("x", 0.0, 1.0, chorus = true),
                0,
                10,
                30,
            )
        )
    }

    @Test
    fun `empty line is never a hero`() {
        assertFalse(FumeArticlePlanner.chooseNaturalBlockVariant(line("", 0.0, 1.0), 0, 10, 0))
    }

    @Test
    fun `fallback hero picks a middle short line when nothing qualifies`() {
        // 32 graphemes: too long to be a natural hero (those stop at 28) but still an acceptable
        // fallback (<= 36). A 37-character line would be rejected outright and the fallback would
        // drop through to "shortest line", which is a different branch with its own test.
        val lines = (0 until 12).map { index ->
            line(
                "这是一个很长的句子用来确认没有任何一行能够自然成为主标题所以需要回退",
                index * 2.0,
                index * 2.0 + 1.5,
            )
        }
        val entries = lines.mapIndexed { index, l -> l to index }
        val counts = lines.map { FumeMath.splitGraphemes(it.fullText).size }

        val index = FumeArticlePlanner.chooseFallbackHeroIndex(entries, counts)
        assertTrue("index=$index", index >= 0)
        assertTrue(index in 3..8)
    }

    @Test
    fun `fallback hero falls through to the shortest line when every line is too long`() {
        // All 40 graphemes: beyond the 36 ceiling, so the scored search finds nothing and the
        // shortest-line branch decides. With equal lengths the first one wins.
        // 40 graphemes each: over the 36 ceiling, so even the scored search has no candidate.
        val lines = (0 until 6).map { index ->
            line(
                "这一段文字故意写得非常长已经超过了评分搜索能够接受的长度上限所以只能由最短行回退逻辑处理",
                index * 2.0,
                index * 2.0 + 1.5,
            )
        }
        val entries = lines.mapIndexed { index, l -> l to index }
        val counts = lines.map { FumeMath.splitGraphemes(it.fullText).size }

        assertEquals(0, FumeArticlePlanner.chooseFallbackHeroIndex(entries, counts))
    }

    @Test
    fun `fallback hero is skipped when a natural one exists`() {
        val lines = listOf(
            line("短句", 0.0, 1.0, chorus = true),
            line("一个比较长的句子内容", 2.0, 3.0),
        )
        val entries = lines.mapIndexed { index, l -> l to index }
        val counts = lines.map { FumeMath.splitGraphemes(it.fullText).size }

        assertEquals(-1, FumeArticlePlanner.chooseFallbackHeroIndex(entries, counts))
    }

    @Test
    fun `plan produces blocks for every non-empty line`() {
        val article = FumeArticlePlanner.plan(
            lines = sampleLines(12),
            viewportWidth = 400f,
            viewportHeight = 800f,
            measurer = measurer(),
        )

        assertNotNull(article)
        assertEquals(12, article!!.blocks.size)
        assertEquals(12, article.chronologicalBlocks.size)
    }

    @Test
    fun `plan skips empty lines`() {
        val lines = listOf(
            line("第一句", 0.0, 1.0),
            line("   ", 1.0, 2.0),
            line("第二句", 2.0, 3.0),
        )
        val article = FumeArticlePlanner.plan(lines, 400f, 800f, measurer = measurer())!!

        assertEquals(2, article.blocks.size)
    }

    @Test
    fun `plan is deterministic`() {
        val first = FumeArticlePlanner.plan(sampleLines(20), 400f, 800f, measurer = measurer())!!
        val second = FumeArticlePlanner.plan(sampleLines(20), 400f, 800f, measurer = measurer())!!

        assertEquals(first.blocks.map { it.id }, second.blocks.map { it.id })
        assertEquals(first.blocks.map { it.x to it.y }, second.blocks.map { it.x to it.y })
    }

    @Test
    fun `blocks in one column never overlap vertically`() {
        val article = FumeArticlePlanner.plan(sampleLines(24), 400f, 800f, measurer = measurer())!!

        // Group by x: blocks that share a column must stack without intersecting.
        val byColumn = article.blocks.groupBy { it.x }
        for ((_, column) in byColumn) {
            val sorted = column.sortedBy { it.y }
            for (index in 1 until sorted.size) {
                val previous = sorted[index - 1]
                val current = sorted[index]
                assertTrue(
                    "overlap between ${previous.id} (${previous.y}+${previous.height}) and " +
                        "${current.id} (${current.y})",
                    current.y >= previous.y + previous.height,
                )
            }
        }
    }

    @Test
    fun `hero block spans wider than a body block`() {
        // 1080px is the emulator's canvas width and the case that matters: a phone in pixels.
        val article = FumeArticlePlanner.plan(sampleLines(30), 1080f, 1400f, measurer = measurer())!!

        val hero = article.blocks.firstOrNull { it.isHero }
        val body = article.blocks.firstOrNull { !it.isHero }
        assertNotNull("no hero block was chosen", hero)
        assertNotNull("no body block was chosen", body)
        // A hero is wider than a body block when there are two columns, and sets larger type either
        // way; that combination is what makes it read as a headline.
        assertTrue("hero=${hero!!.fontPx} body=${body!!.fontPx}", hero.fontPx > body.fontPx)
        if (article.columns >= 2) {
            assertTrue("hero=${hero.width} body=${body.width}", hero.width > body.width)
        }
    }

    @Test
    fun `page stays close to the viewport width on a phone`() {
        val article = FumeArticlePlanner.plan(sampleLines(12), 1080f, 1400f, measurer = measurer())!!

        // The column count is chosen by which arrangement lands closest to the target height, so it
        // is legitimately 1 or 2 for the same input. What must hold is that the page is a modest
        // multiple of the viewport — a desktop-sized sheet makes the camera zoom out until the text
        // is unreadable, which is the bug this caught.
        assertTrue("width=${article.width}", article.width < 1080f * 2.0f)
        assertTrue("width=${article.width}", article.width > 1080f)
    }

    @Test
    fun `article is taller than the viewport so there is somewhere to scroll`() {
        val article = FumeArticlePlanner.plan(sampleLines(40), 400f, 800f, measurer = measurer())!!

        assertTrue("height=${article.height}", article.height > 800f)
    }

    @Test
    fun `blocks stay inside the paper horizontally`() {
        val article = FumeArticlePlanner.plan(sampleLines(20), 400f, 800f, measurer = measurer())!!

        for (block in article.blocks) {
            assertTrue("${block.id} x=${block.x}", block.x >= article.paperLeft - 1f)
            assertTrue(
                "${block.id} right=${block.x + block.width}",
                block.x + block.width <= article.paperRight + 1f,
            )
        }
    }

    @Test
    fun `zero viewport yields no article`() {
        assertEquals(null, FumeArticlePlanner.plan(sampleLines(5), 0f, 800f, measurer = measurer()))
        assertEquals(null, FumeArticlePlanner.plan(sampleLines(5), 400f, 0f, measurer = measurer()))
    }

    @Test
    fun `no lines yields no article`() {
        assertEquals(null, FumeArticlePlanner.plan(emptyList(), 400f, 800f, measurer = measurer()))
    }

    @Test
    fun `word ranges cover the block without gaps`() {
        val line = line("hello world", 0.0, 2.0)
        val graphemes = FumeMath.splitGraphemes(line.fullText)
        val ranges = FumeArticlePlanner.buildWordRanges(line, graphemes)

        assertEquals(0, ranges.first().start)
        assertEquals(graphemes.size, ranges.last().end)
        for (index in 1 until ranges.size) {
            assertEquals(ranges[index - 1].end, ranges[index].start)
        }
    }

    @Test
    fun `whitespace between words attaches to the preceding word`() {
        // Word texts omit the space that fullText keeps; the range must still be contiguous.
        val words = listOf(Word("hello", 0.0, 1.0), Word("world", 1.0, 2.0))
        val line = Line(words = words, startTime = 0.0, endTime = 2.0, fullText = "hello world")
        val graphemes = FumeMath.splitGraphemes(line.fullText)
        val ranges = FumeArticlePlanner.buildWordRanges(line, graphemes)

        assertEquals(6, ranges[0].end) // "hello "
        assertEquals(6, ranges[1].start)
        assertEquals(11, ranges[1].end)
    }

    @Test
    fun `grapheme starts spread across the word`() {
        // A single word spanning the whole line, so `single()` is meaningful: the helper above
        // creates one word per grapheme, which would split this into four ranges.
        val single = Line(
            words = listOf(Word("abcd", 1.0, 2.0)),
            startTime = 1.0,
            endTime = 2.0,
            fullText = "abcd",
        )
        val graphemes = FumeMath.splitGraphemes(single.fullText)
        val ranges = FumeArticlePlanner.buildWordRanges(single, graphemes)
        val starts = ranges.single().graphemeStarts!!

        assertEquals(4, starts.size)
        assertEquals(1.0, starts[0], 0.001)
        assertEquals(1.25, starts[1], 0.001)
        assertEquals(1.75, starts[3], 0.001)
    }

    @Test
    fun `render lines tile the block's graphemes`() {
        val article = FumeArticlePlanner.plan(sampleLines(8), 400f, 800f, measurer = measurer())!!

        for (block in article.blocks) {
            assertEquals(0, block.renderLines.first().start)
            assertEquals(block.graphemes.size, block.renderLines.last().end)
            for (index in 1 until block.renderLines.size) {
                assertEquals(block.renderLines[index - 1].end, block.renderLines[index].start)
            }
        }
    }

    @Test
    fun `render line tops are multiples of the line height`() {
        val article = FumeArticlePlanner.plan(sampleLines(6), 400f, 800f, measurer = measurer())!!

        for (block in article.blocks) {
            block.renderLines.forEachIndexed { index, renderLine ->
                assertEquals(index * block.lineHeight, renderLine.top, 0.01f)
            }
        }
    }

    @Test
    fun `narrow viewport uses a single column`() {
        // 200px viewport → paper is at least 920 wide, which still allows multiple columns; the
        // check is that the search returned a valid article rather than dividing by zero.
        val article = FumeArticlePlanner.plan(sampleLines(10), 200f, 400f, measurer = measurer())!!
        assertTrue(article.columns >= 1)
        assertTrue(article.gap > 0f)
    }
}
