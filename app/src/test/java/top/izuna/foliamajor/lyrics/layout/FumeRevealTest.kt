package top.izuna.foliamajor.lyrics.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.izuna.foliamajor.lyrics.Line
import top.izuna.foliamajor.lyrics.Word

/**
 * Tests for the printing timeline and camera.
 *
 * The printing rules are where fume's feel comes from, so these assert the boundaries precisely:
 * nothing before the line starts, one character immediately when it does, everything once it ends.
 */
class FumeRevealTest {

    private fun line(
        text: String = "abcdefgh",
        start: Double = 10.0,
        end: Double = 14.0,
    ): Line {
        val graphemes = FumeMath.splitGraphemes(text)
        val per = (end - start) / graphemes.size
        return Line(
            words = graphemes.mapIndexed { index, g -> Word(g, start + index * per, start + (index + 1) * per) },
            startTime = start,
            endTime = end,
            fullText = text,
        )
    }

    private fun ranges(line: Line): List<FumeWordRange> =
        FumeArticlePlanner.buildWordRanges(line, FumeMath.splitGraphemes(line.fullText))

    @Test
    fun `nothing prints before the line starts`() {
        val l = line()
        assertEquals(0, FumeReveal.printedGraphemeCount(l, ranges(l), 8, 9.99))
        assertEquals(0f, FumeReveal.printedGraphemeProgress(l, ranges(l), 8, 9.99), 0.001f)
    }

    @Test
    fun `everything prints once the line ends`() {
        val l = line()
        assertEquals(8, FumeReveal.printedGraphemeCount(l, ranges(l), 8, 14.0))
        assertEquals(8f, FumeReveal.printedGraphemeProgress(l, ranges(l), 8, 14.0), 0.001f)
        assertEquals(8, FumeReveal.printedGraphemeCount(l, ranges(l), 8, 99.0))
    }

    @Test
    fun `the first grapheme appears as soon as the line starts`() {
        // Without the desktop's +0.2 before flooring this returns 0 and every line appears to pause.
        val l = line()
        assertEquals(1, FumeReveal.printedGraphemeCount(l, ranges(l), 8, 10.0))
    }

    @Test
    fun `printed count grows monotonically`() {
        val l = line()
        val rs = ranges(l)
        var previous = -1
        var time = 9.5
        while (time <= 14.5) {
            val count = FumeReveal.printedGraphemeCount(l, rs, 8, time)
            assertTrue("t=$time count=$count previous=$previous", count >= previous)
            previous = count
            time += 0.05
        }
        assertEquals(8, previous)
    }

    @Test
    fun `empty line prints nothing`() {
        val l = line("", 10.0, 12.0)
        assertEquals(0, FumeReveal.printedGraphemeCount(l, emptyList(), 0, 11.0))
    }

    @Test
    fun `no word ranges falls back to an even spread`() {
        val l = line("abcdefgh", 0.0, 4.0)
        val halfway = FumeReveal.printedGraphemeCount(l, emptyList(), 8, 2.0)

        assertTrue("halfway=$halfway", halfway in 3..5)
    }

    @Test
    fun `a zero length line still terminates`() {
        val l = line("ab", 5.0, 5.0)
        // endTime == startTime: the "line is over" branch fires immediately.
        assertEquals(2, FumeReveal.printedGraphemeCount(l, ranges(l), 2, 5.0))
    }

    @Test
    fun `progress is fractional inside a word`() {
        val l = line("abcd", 0.0, 4.0)
        val value = FumeReveal.printedGraphemeProgress(l, ranges(l), 4, 2.5)

        // Halfway through the line: around two characters in, not snapped to an integer.
        assertTrue("value=$value", value > 1.8f && value < 3.2f)
    }

    @Test
    fun `cutoff uses the next line start when it comes first`() {
        val l = line("ab", 0.0, 10.0)

        // No next line: the render end wins.
        assertEquals(l.renderEndTime, FumeReveal.linePassCutoffTime(l, null), 0.001)
        // Next line at 4s: the line is cut there.
        assertEquals(4.0, FumeReveal.linePassCutoffTime(l, 4.0), 0.001)
        // Next line later than the render end: the render end still wins.
        assertEquals(l.renderEndTime, FumeReveal.linePassCutoffTime(l, 99.0), 0.001)
    }

    @Test
    fun `progress with cutoff reaches one at the cutoff`() {
        assertEquals(1f, FumeReveal.progressWithCutoff(0.0, 10.0, 4.0, 4.0), 0.001f)
        assertEquals(0.5f, FumeReveal.progressWithCutoff(0.0, 10.0, 2.0, 4.0), 0.001f)
    }

    @Test
    fun `progress with cutoff clamps`() {
        assertEquals(0f, FumeReveal.progressWithCutoff(1.0, 2.0, 0.0, 5.0), 0.001f)
        assertEquals(1f, FumeReveal.progressWithCutoff(1.0, 2.0, 99.0, 5.0), 0.001f)
    }
}

/**
 * Tests for the camera state machine.
 *
 * The spring is exercised by stepping frames rather than by asserting closed-form positions: what
 * matters is that it settles, does not overshoot wildly, and never diverges — the exact trajectory
 * is a tuning knob.
 */
class FumeCameraTest {

    private fun measurer() = FumeGlyphMeasurer { text, _ ->
        FloatArray(FumeMath.splitGraphemes(text).size) { 10f }
    }

    private fun lines(count: Int): List<Line> = (0 until count).map { index ->
        val start = index * 3.0
        val text = "第${index}句歌词内容"
        val graphemes = FumeMath.splitGraphemes(text)
        val per = 2.5 / graphemes.size
        Line(
            words = graphemes.mapIndexed { i, g -> Word(g, start + i * per, start + (i + 1) * per) },
            startTime = start,
            endTime = start + 2.5,
            fullText = text,
        )
    }

    private fun article() = FumeArticlePlanner.plan(
        lines = lines(10),
        viewportWidth = 400f,
        viewportHeight = 800f,
        measurer = measurer(),
    )!!

    @Test
    fun `spring approaches its target monotonically`() {
        var spring = FumeSpring(0f)
        val target = 100f
        var previous = -1f

        repeat(120) {
            spring = spring.step(target, 1f / 60f, 190f, 26f)
            assertTrue("overshoot at ${spring.value}", spring.value <= target + 0.5f)
            assertTrue(spring.value >= previous)
            previous = spring.value
        }

        assertEquals(target, spring.value, 1f)
    }

    @Test
    fun `spring settles and stops`() {
        var spring = FumeSpring(0f)
        repeat(600) { spring = spring.step(50f, 1f / 60f, 190f, 26f) }

        assertEquals(50f, spring.value, 0.01f)
        assertEquals(0f, spring.velocity, 0.01f)
    }

    @Test
    fun `spring does not diverge on a long frame`() {
        // dt is clamped internally; a 200ms stall must not make the next frame explode.
        var spring = FumeSpring(0f)
        repeat(20) { spring = spring.step(1000f, 0.2f, 190f, 26f) }

        assertTrue("value=${spring.value}", spring.value.isFinite())
        assertTrue("value=${spring.value}", spring.value <= 1000.1f)
    }

    @Test
    fun `sync places a fresh camera at the page centre`() {
        val a = article()
        val state = FumeCamera.syncToArticle(FumeCameraState(), a)

        assertTrue(state.initialized)
        assertEquals(a.width * 0.5f, state.x.value, 0.01f)
        assertEquals(a.height * 0.5f, state.y.value, 0.01f)
        assertEquals(1.18f, state.scale.value, 0.01f)
    }

    @Test
    fun `sync keeps a running camera inside the article`() {
        val a = article()
        val running = FumeCameraState(x = FumeSpring(-500f), y = FumeSpring(-500f), initialized = true)
        val state = FumeCamera.syncToArticle(running, a)

        assertEquals(0f, state.x.value, 0.001f)
        assertEquals(0f, state.y.value, 0.001f)
    }

    @Test
    fun `sync clears initialization for a null article`() {
        val state = FumeCamera.syncToArticle(
            FumeCameraState(initialized = true),
            null,
        )
        assertTrue(!state.initialized)
    }

    @Test
    fun `focus block follows the active line`() {
        val a = article()
        val block = FumeCamera.resolveFocusBlock(a, 4, 12.5)

        assertNotNull(block)
        assertEquals(4, block!!.sourceLineIndex)
    }

    @Test
    fun `focus block falls back to the last printed block between lines`() {
        val a = article()
        // Lines are 2.5s long with 0.5s gaps: line 3 spans 9..11.5 and line 4 starts at 12.0.
        // At 11.9 nothing is active, so the reverse walk must land on line 3 — not on line 4, whose
        // first grapheme prints at exactly its start time.
        val block = FumeCamera.resolveFocusBlock(a, -1, 11.9)

        assertNotNull(block)
        assertEquals(3, block!!.sourceLineIndex)
    }

    @Test
    fun `a line that has just started already counts as printed`() {
        val a = article()
        // At exactly 12.0 line 4 prints its first grapheme, which is what makes the camera move on
        // the beat rather than between beats.
        val block = FumeCamera.resolveFocusBlock(a, -1, 12.0)
        assertEquals(4, block!!.sourceLineIndex)
    }

    @Test
    fun `focus block is the last one after the song ends`() {
        val a = article()
        val block = FumeCamera.resolveFocusBlock(a, -1, 999.0)

        assertEquals(a.chronologicalBlocks.last().sourceLineIndex, block!!.sourceLineIndex)
    }

    @Test
    fun `focus block falls back to the first block before anything prints`() {
        val a = article()
        val block = FumeCamera.resolveFocusBlock(a, -1, 0.0)

        assertNotNull(block)
        assertEquals(a.chronologicalBlocks.first().sourceLineIndex, block!!.sourceLineIndex)
    }

    @Test
    fun `stepping focus point stays inside its block`() {
        val a = article()
        val block = a.blocks.first()

        for (progress in listOf(0f, 1f, 5f, 50f, 1000f)) {
            val point = FumeCamera.steppingFocusPoint(block, progress)
            assertTrue("x=${point.x}", point.x >= block.x - 0.5f)
            assertTrue("x=${point.x}", point.x <= block.x + block.width + 0.5f)
            assertTrue("y=${point.y}", point.y >= block.y - 0.5f)
            assertTrue("y=${point.y}", point.y <= block.y + block.height + 0.5f)
        }
    }

    @Test
    fun `stepping focus point advances with progress`() {
        val a = article()
        // A block with a single render line, so the x position tracks the printed offset.
        val block = a.blocks.first { it.renderLines.size == 1 }

        val early = FumeCamera.steppingFocusPoint(block, 0f)
        val late = FumeCamera.steppingFocusPoint(block, block.graphemes.size.toFloat())

        assertTrue("early=${early.x} late=${late.x}", late.x > early.x)
    }

    @Test
    fun `scale for block stays within limits`() {
        val a = article()
        for (block in a.blocks) {
            val scale = FumeCamera.scaleForBlock(block, 400f, 800f)
            assertTrue("scale=$scale", scale in FumeCamera.SCALE_MIN..2.2f)
        }
    }

    @Test
    fun `overview frames every block`() {
        val a = article()
        val overview = FumeCamera.overviewCamera(a, 400f, 800f)

        assertTrue(overview.scale in FumeCamera.SCALE_MIN..0.72f)
        // The overview centre must lie inside the article.
        assertTrue(overview.x in 0f..a.width)
        assertTrue(overview.y in 0f..a.height)
    }

    @Test
    fun `step converges on the active block`() {
        val a = article()
        val viewportWidth = 400f
        val viewportHeight = 800f
        var state = FumeCamera.syncToArticle(FumeCameraState(), a)

        // Line 5 spans 15..17.5; run a second of frames at its midpoint.
        var point = FumeCamera.initialPoint(a)
        repeat(90) {
            val (next, p) = FumeCamera.step(
                state = state,
                article = a,
                currentTime = 16.0,
                nowMillis = it * 16L,
                dt = 1f / 60f,
                currentLineIndex = 5,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                showOverview = false,
            )
            state = next
            point = p
        }

        val block = a.blocks.first { it.sourceLineIndex == 5 }
        assertEquals(5, state.targetLineIndex)
        // Settled near the block rather than still on the far side of the page.
        assertTrue(
            "camera=(${point.x},${point.y}) block=(${block.x},${block.y})",
            kotlin.math.abs(point.y - block.y) < block.height + 200f,
        )
    }

    @Test
    fun `overview target is reported when requested`() {
        val a = article()
        val state = FumeCamera.syncToArticle(FumeCameraState(), a)
        val (next, _) = FumeCamera.step(
            state = state,
            article = a,
            currentTime = 0.0,
            nowMillis = 0L,
            dt = 1f / 60f,
            currentLineIndex = -1,
            viewportWidth = 400f,
            viewportHeight = 800f,
            showOverview = true,
        )

        assertEquals(FumeCamera.OVERVIEW_SOURCE, next.targetLineIndex)
    }

    @Test
    fun `camera stays finite over many frames`() {
        val a = article()
        var state = FumeCamera.syncToArticle(FumeCameraState(), a)

        repeat(600) { frame ->
            val time = frame * 0.05
            val lineIndex = (time / 3.0).toInt()
            val (next, point) = FumeCamera.step(
                state = state,
                article = a,
                currentTime = time,
                nowMillis = frame * 50L,
                dt = 0.05f,
                currentLineIndex = lineIndex,
                viewportWidth = 400f,
                viewportHeight = 800f,
                showOverview = false,
            )
            state = next
            assertTrue(point.x.isFinite() && point.y.isFinite() && point.scale.isFinite())
            assertTrue(point.scale in FumeCamera.SCALE_MIN..FumeCamera.SCALE_MAX)
        }
    }

    @Test
    fun `initial point before the first frame is the page centre`() {
        assertEquals(FumeCameraPoint(0f, 0f, 1f), FumeCamera.initialPoint(null))

        val a = article()
        val point = FumeCamera.initialPoint(a)
        assertEquals(a.width * 0.5f, point.x, 0.01f)
    }

    @Test
    fun `empty article has no focus block`() {
        // Constructed directly because the planner refuses to build an empty article.
        val empty = FumeArticleLayout(
            width = 100f, height = 100f, viewportHeight = 800f, columns = 1, gap = 8f,
            paperLeft = 0f, paperTop = 0f, paperRight = 100f, paperBottom = 100f,
            blocks = emptyList(), chronologicalBlocks = emptyList(),
            firstRenderableStartTime = Double.POSITIVE_INFINITY,
            lastChronologicalRenderEndTime = Double.NEGATIVE_INFINITY,
        )

        assertNull(FumeCamera.resolveFocusBlock(empty, 0, 5.0))
    }
}

/** Tests for the overview trigger. */
class FumeOverviewTest {

    private fun measurer() = FumeGlyphMeasurer { text, _ ->
        FloatArray(FumeMath.splitGraphemes(text).size) { 10f }
    }

    private fun article(): FumeArticleLayout {
        val lines = (0 until 8).map { index ->
            val start = index * 3.0
            val text = "第${index}句"
            val graphemes = FumeMath.splitGraphemes(text)
            val per = 2.0 / graphemes.size
            Line(
                words = graphemes.mapIndexed { i, g -> Word(g, start + i * per, start + (i + 1) * per) },
                startTime = start,
                endTime = start + 2.0,
                fullText = text,
            )
        }
        return FumeArticlePlanner.plan(lines, 1080f, 1400f, measurer = measurer())!!
    }

    @Test
    fun `overview is shown well before the first line`() {
        val a = article()
        // First line starts at 0, so there is no lead-in window at all for this fixture.
        assertTrue(FumeCamera.shouldShowOverview(a, -10.0))
    }

    @Test
    fun `overview is hidden while the song is being sung`() {
        val a = article()
        assertTrue(!FumeCamera.shouldShowOverview(a, 5.0))
    }

    @Test
    fun `overview returns after the last line`() {
        val a = article()
        assertTrue(FumeCamera.shouldShowOverview(a, a.lastChronologicalRenderEndTime + 10.0))
    }

    @Test
    fun `a short gap between lines keeps the camera on the block`() {
        val a = article()
        // 0.5s of silence mid-song must not trigger the pull-back.
        assertTrue(!FumeCamera.shouldShowOverview(a, a.lastChronologicalRenderEndTime - 1.0))
    }
}
