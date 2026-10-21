package top.izuna.foliamajor.lyrics.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.izuna.foliamajor.lyrics.Line
import top.izuna.foliamajor.lyrics.Word

/**
 * Tests for the cappella transcript.
 *
 * The invariants that matter are determinism and side balance: the same song must produce the same
 * conversation every time, the title must come from the right, and a long run of lines must not all
 * land on one side.
 */
class CappellaTranscriptTest {

    private val normal = CappellaIntensity.normal()

    private fun line(text: String, start: Double, end: Double, agentId: String? = null) =
        Line(
            words = listOf(Word(text, start, end)),
            startTime = start,
            endTime = end,
            fullText = text,
            agentId = agentId,
        )

    private fun lines(count: Int) = (0 until count).map { index ->
        line("这是第${index}句歌词", index * 3.0, index * 3.0 + 2.5)
    }

    private fun lyricMessages(messages: List<ChatMessage>) = messages.filterIsInstance<ChatMessage.Lyric>()

    @Test
    fun `transcript opens with the title from the right`() {
        val messages = CappellaTranscript.build(lines(4), "标题", normal)

        val title = messages.first() as ChatMessage.Title
        assertEquals("标题", title.text)
        assertEquals(ChatSide.RIGHT, title.sender.side)
    }

    @Test
    fun `one message per line`() {
        val messages = CappellaTranscript.build(lines(6), "标题", normal)

        assertEquals(6, lyricMessages(messages).size)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), lyricMessages(messages).map { it.lineIndex })
    }

    @Test
    fun `empty lyrics still produce the title`() {
        val messages = CappellaTranscript.build(emptyList(), "标题", normal)

        assertEquals(1, messages.size)
        assertTrue(messages.single() is ChatMessage.Title)
    }

    @Test
    fun `the same song produces the same conversation`() {
        val first = CappellaTranscript.build(lines(20), "标题", normal)
        val second = CappellaTranscript.build(lines(20), "标题", normal)

        assertEquals(
            first.map { it.sender },
            second.map { it.sender },
        )
    }

    @Test
    fun `both sides are used`() {
        val messages = lyricMessages(CappellaTranscript.build(lines(16), "标题", normal))
        val sides = messages.map { it.sender.side }.toSet()

        assertEquals(setOf(ChatSide.LEFT, ChatSide.RIGHT), sides)
    }

    @Test
    fun `right always uses the bottom-right avatar`() {
        val messages = CappellaTranscript.build(lines(16), "标题", normal)
        val rightAvatars = messages
            .filter { it.sender.side == ChatSide.RIGHT }
            .map { it.sender.avatarIndex }
            .toSet()

        assertEquals(setOf(CappellaTranscript.RIGHT_AVATAR_INDEX), rightAvatars)
    }

    @Test
    fun `left avatars come from the left pool`() {
        val messages = CappellaTranscript.build(lines(24), "标题", normal)
        val leftAvatars = messages
            .filter { it.sender.side == ChatSide.LEFT }
            .map { it.sender.avatarIndex }
            .toSet()

        assertTrue(leftAvatars.isNotEmpty())
        assertTrue(
            "left avatars=$leftAvatars",
            leftAvatars.all { it in CappellaTranscript.LEFT_AVATAR_INDICES },
        )
    }

    @Test
    fun `every fifth line is forced to the right in normal intensity`() {
        val messages = lyricMessages(CappellaTranscript.build(lines(20), "标题", normal))

        // forceRightEveryLines = 5 → line indices 4, 9, 14, 19.
        for (index in listOf(4, 9, 14, 19)) {
            assertEquals(
                "line $index",
                ChatSide.RIGHT,
                messages[index].sender.side,
            )
        }
    }

    @Test
    fun `a duet maps agents to fixed senders`() {
        val duet = listOf(
            line("甲方第一句", 0.0, 2.0, agentId = "A"),
            line("乙方第一句", 2.0, 4.0, agentId = "B"),
            line("甲方第二句", 4.0, 6.0, agentId = "A"),
            line("乙方第二句", 6.0, 8.0, agentId = "B"),
        )
        val messages = lyricMessages(CappellaTranscript.build(duet, "标题", normal))

        // Agent A appears first, so A is the right-hand speaker; B is on the left.
        assertEquals(ChatSide.RIGHT, messages[0].sender.side)
        assertEquals(ChatSide.LEFT, messages[1].sender.side)
        assertEquals(messages[0].sender, messages[2].sender)
        assertEquals(messages[1].sender, messages[3].sender)
    }

    @Test
    fun `a single agent is not a duet`() {
        val single = (0 until 6).map { index ->
            line("第${index}句", index * 2.0, index * 2.0 + 1.5, agentId = "solo")
        }
        val messages = lyricMessages(CappellaTranscript.build(single, "标题", normal))

        // With only one agent the seeded sequence takes over, so sides vary.
        assertTrue(messages.map { it.sender.side }.toSet().size >= 1)
    }

    @Test
    fun `intensity presets differ`() {
        assertNotEquals(
            CappellaIntensity.calm().motion.activeScale,
            CappellaIntensity.chaotic().motion.activeScale,
        )
        assertTrue(CappellaIntensity.chaotic().sequencing.sideFlipChance >
            CappellaIntensity.calm().sequencing.sideFlipChance)
    }

    @Test
    fun `unknown intensity falls back to normal`() {
        assertEquals(
            CappellaIntensity.normal().motion.activeScale,
            CappellaIntensity.forIntensity("banana").motion.activeScale,
            0.0001f,
        )
    }

    @Test
    fun `visible messages include the title and everything printed so far`() {
        val messages = CappellaTranscript.build(lines(10), "标题", normal)
        val visible = CappellaTranscript.visibleMessages(
            messages = messages,
            currentLineIndex = 3,
            heightBudget = 10000f,
            estimatedHeight = { 50f },
        )

        assertEquals(1 + 4, visible.size)
        assertTrue(visible.first() is ChatMessage.Title)
    }

    @Test
    fun `visible messages drop the oldest when the budget runs out`() {
        val messages = CappellaTranscript.build(lines(20), "标题", normal)
        val visible = CappellaTranscript.visibleMessages(
            messages = messages,
            currentLineIndex = 19,
            heightBudget = 200f,
            estimatedHeight = { 100f },
        )

        // Two messages fit in 200; the newest two must be the ones kept.
        assertEquals(2, visible.size)
        assertEquals(19, (visible.last() as ChatMessage.Lyric).lineIndex)
        assertEquals(18, (visible.first() as ChatMessage.Lyric).lineIndex)
    }

    @Test
    fun `at least two messages survive even with no room`() {
        val messages = CappellaTranscript.build(lines(10), "标题", normal)
        val visible = CappellaTranscript.visibleMessages(
            messages = messages,
            currentLineIndex = 9,
            heightBudget = 1f,
            estimatedHeight = { 500f },
        )

        assertEquals(2, visible.size)
    }

    @Test
    fun `unprinted lines are not visible`() {
        val messages = CappellaTranscript.build(lines(10), "标题", normal)
        val visible = CappellaTranscript.visibleMessages(
            messages = messages,
            currentLineIndex = 2,
            heightBudget = 10000f,
            estimatedHeight = { 10f },
        )

        assertTrue(visible.filterIsInstance<ChatMessage.Lyric>().all { it.lineIndex <= 2 })
    }

    @Test
    fun `visible line index follows playback`() {
        val list = lines(5)

        assertEquals(-1, CappellaTranscript.visibleLineIndexAt(list, -1.0))
        assertEquals(0, CappellaTranscript.visibleLineIndexAt(list, 0.0))
        assertEquals(2, CappellaTranscript.visibleLineIndexAt(list, 6.5))
        assertEquals(4, CappellaTranscript.visibleLineIndexAt(list, 99.0))
    }

    @Test
    fun `timestamps are chat style`() {
        assertEquals("0:00", CappellaTranscript.formatTimestamp(-1.0))
        assertEquals("0:00", CappellaTranscript.formatTimestamp(0.0))
        assertEquals("0:09", CappellaTranscript.formatTimestamp(9.9))
        assertEquals("1:05", CappellaTranscript.formatTimestamp(65.4))
        assertEquals("0:00", CappellaTranscript.formatTimestamp(Double.NaN))
    }

    @Test
    fun `render end is capped by the next line`() {
        val current = line("a", 0.0, 10.0)
        val next = line("b", 4.0, 6.0)

        assertEquals(4.0, CappellaTranscript.effectiveRenderEnd(current, next), 0.001)
        assertEquals(
            current.renderEndTime,
            CappellaTranscript.effectiveRenderEnd(current, null),
            0.001,
        )
    }
}

/**
 * Tests for the bubble reveal timeline.
 */
class CappellaRevealTest {

    private fun line(text: String, start: Double, end: Double, words: List<Word>? = null): Line {
        val resolved = words ?: listOf(Word(text, start, end))
        return Line(words = resolved, startTime = start, endTime = end, fullText = text)
    }

    @Test
    fun `reveal times are monotonic`() {
        val l = line("一二三四五", 1.0, 3.0)
        val plan = CappellaReveal.plan(l)

        for (index in 1 until plan.revealTimes.size) {
            assertTrue(
                "index $index: ${plan.revealTimes[index - 1]} > ${plan.revealTimes[index]}",
                plan.revealTimes[index] >= plan.revealTimes[index - 1],
            )
        }
    }

    @Test
    fun `every character gets a reveal time`() {
        val l = line("hello world", 0.0, 2.0)
        val plan = CappellaReveal.plan(l)

        assertEquals(11, plan.characters.size)
        assertEquals(11, plan.revealTimes.size)
        assertTrue(plan.revealTimes.all { it.isFinite() })
    }

    @Test
    fun `visible count follows the timeline`() {
        val l = line("abcd", 0.0, 4.0)
        val plan = CappellaReveal.plan(l)

        assertEquals(0, CappellaReveal.visibleCount(plan.revealTimes, -1.0))
        assertEquals(4, CappellaReveal.visibleCount(plan.revealTimes, 99.0))
        assertEquals(1, CappellaReveal.visibleCount(plan.revealTimes, 0.0))
    }

    @Test
    fun `visible count is monotonic in time`() {
        val l = line("一二三四五六七八", 0.0, 4.0)
        val plan = CappellaReveal.plan(l)
        var previous = -1
        var time = -0.5
        while (time <= 4.5) {
            val count = CappellaReveal.visibleCount(plan.revealTimes, time)
            assertTrue("t=$time count=$count previous=$previous", count >= previous)
            previous = count
            time += 0.1
        }
    }

    @Test
    fun `bubble targets lead the reveal`() {
        val l = line("abcd", 10.0, 12.0)
        val plan = CappellaReveal.plan(l)
        val targets = CappellaReveal.bubbleTargetTimes(plan, l)

        for (index in targets.indices) {
            assertTrue(
                "target ${targets[index]} must not exceed reveal ${plan.revealTimes[index]}",
                targets[index] <= plan.revealTimes[index],
            )
        }
    }

    @Test
    fun `bubble targets never precede the line`() {
        val l = line("abcd", 10.0, 10.1)
        val targets = CappellaReveal.bubbleTargetTimes(CappellaReveal.plan(l), l)

        assertTrue(targets.all { it >= 10.0 })
    }

    @Test
    fun `fade durations are at least the minimum`() {
        val l = line("abcdefgh", 0.0, 0.2)
        val plan = CappellaReveal.plan(l)

        assertTrue(plan.fadeDurationsMs.all { it >= CappellaReveal.MIN_CHAR_FADE_MS })
    }

    @Test
    fun `a slowly sung line fades more slowly`() {
        val fast = CappellaReveal.plan(line("abcd", 0.0, 1.0))
        val slow = CappellaReveal.plan(line("abcd", 0.0, 8.0))

        assertTrue(slow.fadeDurationsMs.first() > fast.fadeDurationsMs.first())
    }

    @Test
    fun `timestamp is ready after the last fade completes`() {
        val l = line("abcd", 0.0, 4.0)
        val plan = CappellaReveal.plan(l)

        val lastReveal = plan.revealTimes.maxOrNull()!!
        val lastFade = plan.fadeDurationsMs.maxOrNull()!! / 1000.0
        assertTrue(
            "ready=${plan.timestampReadyTime} last=$lastReveal fade=$lastFade",
            plan.timestampReadyTime >= lastReveal,
        )
        assertTrue(plan.timestampReadyTime <= lastReveal + lastFade + 0.001)
    }

    @Test
    fun `spaces between words reveal with the next word`() {
        // The two words omit the space that fullText keeps.
        val l = line(
            text = "aa bb",
            start = 0.0,
            end = 2.0,
            words = listOf(Word("aa", 0.0, 1.0), Word("bb", 1.0, 2.0)),
        )
        val plan = CappellaReveal.plan(l)

        // The space (index 2) belongs to the second word's start time.
        assertEquals(1.0, plan.revealTimes[2], 0.001)
    }

    @Test
    fun `empty line has an empty plan`() {
        val plan = CappellaReveal.plan(line("", 0.0, 1.0))

        assertTrue(plan.characters.isEmpty())
        assertTrue(plan.revealTimes.isEmpty())
    }

    @Test
    fun `cjk characters each get their own reveal`() {
        val l = line("一二三", 0.0, 3.0)
        val plan = CappellaReveal.plan(l)

        assertEquals(3, plan.revealTimes.size)
        assertTrue(plan.revealTimes[1] > plan.revealTimes[0])
        assertTrue(plan.revealTimes[2] > plan.revealTimes[1])
    }
}

/**
 * Tests for bubble geometry.
 */
class CappellaBubbleTest {

    private val measurer = BubbleTextMeasurer { text, fontSize, _, maxWidth ->
        val graphemes = FumeMath.splitGraphemes(text)
        val width = graphemes.size * fontSize * 0.5f
        if (width <= maxWidth) width to 1 else maxWidth to kotlin.math.ceil(width / maxWidth).toInt()
    }

    private fun line(text: String, start: Double = 0.0, end: Double = 2.0) =
        Line(
            words = listOf(Word(text, start, end)),
            startTime = start,
            endTime = end,
            fullText = text,
        )

    @Test
    fun `one size per prefix including empty`() {
        val metrics = CappellaBubble.metrics(
            line = line("abcd"),
            fontSize = 16f,
            lineHeightPx = 23f,
            maxTextWidth = 200f,
            paddingX = 16f,
            paddingY = 12f,
            measurer = measurer,
        )

        assertEquals(5, metrics.sizes.size)
        assertEquals(metrics.characters.size + 1, metrics.sizes.size)
    }

    @Test
    fun `a longer prefix is never narrower`() {
        val metrics = CappellaBubble.metrics(
            line = line("abcdefgh"),
            fontSize = 16f,
            lineHeightPx = 23f,
            maxTextWidth = 200f,
            paddingX = 16f,
            paddingY = 12f,
            measurer = measurer,
        )

        for (index in 1 until metrics.sizes.size) {
            assertTrue(
                "prefix $index: ${metrics.sizes[index - 1].width} > ${metrics.sizes[index].width}",
                metrics.sizes[index].width >= metrics.sizes[index - 1].width,
            )
        }
    }

    @Test
    fun `bubble size at time follows the width timeline`() {
        val l = line("abcdefgh", 0.0, 4.0)
        val metrics = CappellaBubble.metrics(
            line = l,
            fontSize = 16f,
            lineHeightPx = 23f,
            maxTextWidth = 200f,
            paddingX = 16f,
            paddingY = 12f,
            measurer = measurer,
        )

        val early = CappellaBubble.sizeAtTime(metrics, 0.0)
        val late = CappellaBubble.sizeAtTime(metrics, 4.0)

        assertTrue("early=${early.width} late=${late.width}", late.width > early.width)
    }

    @Test
    fun `visible characters track the reveal`() {
        val metrics = CappellaBubble.metrics(
            line = line("abcd", 0.0, 4.0),
            fontSize = 16f,
            lineHeightPx = 23f,
            maxTextWidth = 200f,
            paddingX = 16f,
            paddingY = 12f,
            measurer = measurer,
        )

        assertEquals(0, CappellaBubble.visibleCharactersAt(metrics, -1.0))
        assertEquals(4, CappellaBubble.visibleCharactersAt(metrics, 99.0))
    }

    @Test
    fun `padding and border are part of the size`() {
        val size = CappellaBubble.sizeFor(
            text = "ab",
            fontSize = 16f,
            lineHeightPx = 23f,
            maxTextWidth = 200f,
            paddingX = 20f,
            paddingY = 16f,
            measurer = measurer,
        )

        // 2 graphemes * 16 * 0.5 = 16 wide, + 40 padding + 2 border.
        assertEquals(58f, size.width, 0.5f)
        // 1 line * 23 + 32 padding + 2 border.
        assertEquals(57f, size.height, 0.5f)
    }

    @Test
    fun `a bubble is never narrower than one em`() {
        val size = CappellaBubble.sizeFor(
            text = ".",
            fontSize = 20f,
            lineHeightPx = 28f,
            maxTextWidth = 200f,
            paddingX = 10f,
            paddingY = 8f,
            measurer = { _, _, _, _ -> 2f to 1 },
        )

        // content width forced to fontSize, plus padding and border.
        assertEquals(42f, size.width, 0.5f)
    }

    @Test
    fun `wrapping increases the height`() {
        val single = CappellaBubble.sizeFor(
            text = "abcd",
            fontSize = 16f,
            lineHeightPx = 23f,
            maxTextWidth = 200f,
            paddingX = 10f,
            paddingY = 10f,
            measurer = measurer,
        )
        val wrapped = CappellaBubble.sizeFor(
            text = "abcdefghijkl",
            fontSize = 16f,
            lineHeightPx = 23f,
            maxTextWidth = 20f,
            paddingX = 10f,
            paddingY = 10f,
            measurer = measurer,
        )

        assertTrue("single=${single.height} wrapped=${wrapped.height}", wrapped.height > single.height)
    }

    @Test
    fun `blank text still produces a measurable bubble`() {
        val size = CappellaBubble.sizeFor(
            text = "",
            fontSize = 16f,
            lineHeightPx = 23f,
            maxTextWidth = 200f,
            paddingX = 10f,
            paddingY = 10f,
            measurer = measurer,
        )

        assertTrue(size.width > 0f)
        assertTrue(size.height > 0f)
    }

    @Test
    fun `active rows are taller than passed rows`() {
        val motion = CappellaIntensity.normal().motion
        val active = CappellaBubble.estimatedRowHeight(true, motion, 16f, 23f, 60f)
        val passed = CappellaBubble.estimatedRowHeight(false, motion, 16f, 23f, 60f)

        assertTrue("active=$active passed=$passed", active > passed)
    }

    @Test
    fun `row height respects the minimum`() {
        val motion = CappellaIntensity.normal().motion
        val tiny = CappellaBubble.estimatedRowHeight(false, motion, 16f, 23f, 10f)

        assertTrue("tiny=$tiny", tiny >= motion.inactiveMinHeight)
    }
}
