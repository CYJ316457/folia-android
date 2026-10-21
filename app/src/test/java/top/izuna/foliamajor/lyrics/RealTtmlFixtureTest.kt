package top.izuna.foliamajor.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end TTML parsing against a real AMLL TTML DB file.
 *
 * `qingtian.ttml` is the actual document served by `api.amll.dev` for NetEase id 186016 (晴天 /
 * 周杰伦), fetched 2026-10-17. Hand-written fixtures prove the parser follows the rules I read in
 * AMLL's source; this file proves it survives a document the rules were written for — 62 lines,
 * 494 syllables, per-character timing across the whole song.
 *
 * If a future change breaks real-world parsing but not the synthetic fixtures, this is the test that
 * catches it.
 */
class RealTtmlFixtureTest {

    private fun loadFixture(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("qingtian.ttml")) {
            "qingtian.ttml is missing from test resources"
        }.bufferedReader().use { it.readText() }

    @Test
    fun `parses every line of the document`() {
        val result = parseTTML(loadFixture(), includeInterludes = false)

        // The file has 62 <p> elements. Interludes are disabled so this is the raw line count.
        assertEquals(62, result.lines.size)
    }

    @Test
    fun `reports word-by-word timing`() {
        val result = parseTTML(loadFixture(), includeInterludes = false)

        assertTrue(result.isWordByWord)
    }

    @Test
    fun `splits cjk into one word per syllable`() {
        val result = parseTTML(loadFixture(), includeInterludes = false)
        val firstLine = result.lines.first()

        // The first line is 故事的小黄花, one <span> per character.
        assertEquals("故事的小黄花", firstLine.fullText)
        assertEquals(listOf("故", "事", "的", "小", "黄", "花"), firstLine.words.map { it.text })
    }

    @Test
    fun `preserves the syllable timings from the file`() {
        val result = parseTTML(loadFixture(), includeInterludes = false)
        val firstLine = result.lines.first()

        // <p begin="00:29.231" end="00:32.723"> with spans at 29.231, 29.692, 30.057, ...
        assertEquals(29.231, firstLine.startTime, 0.001)
        assertEquals(32.723, firstLine.endTime, 0.001)
        assertEquals(29.231, firstLine.words[0].startTime, 0.001)
        assertEquals(29.692, firstLine.words[1].startTime, 0.001)
        assertEquals(30.057, firstLine.words[2].startTime, 0.001)
    }

    @Test
    fun `lines are in chronological order and do not overlap backwards`() {
        val result = parseTTML(loadFixture(), includeInterludes = false)

        result.lines.zipWithNext().forEach { (current, next) ->
            assertTrue(
                "line '${current.fullText}' starts after the next line",
                current.startTime <= next.startTime,
            )
        }
    }

    @Test
    fun `every line has words and render hints`() {
        val result = parseTTML(loadFixture(), includeInterludes = false)

        result.lines.forEach { line ->
            assertTrue("line '${line.fullText}' has no words", line.words.isNotEmpty())
            assertNotNull("line '${line.fullText}' has no render hints", line.renderHints)
        }
    }

    @Test
    fun `word times stay inside their line`() {
        val result = parseTTML(loadFixture(), includeInterludes = false)

        result.lines.forEach { line ->
            line.words.forEach { word ->
                assertTrue(
                    "word '${word.text}' in '${line.fullText}' starts before its line",
                    word.startTime >= line.startTime - 0.001,
                )
                assertTrue(
                    "word '${word.text}' in '${line.fullText}' ends after its line",
                    word.endTime <= line.endTime + 0.001,
                )
            }
        }
    }

    @Test
    fun `reads the agent from the metadata head`() {
        val result = parseTTML(loadFixture(), includeInterludes = false)

        // Every line in this file is sung by agent v1.
        assertTrue(result.lines.all { it.agentId == "v1" })
    }

    @Test
    fun `reads the itunes line keys`() {
        val result = parseTTML(loadFixture(), includeInterludes = false)

        assertEquals("L1", result.lines.first().id)
    }

    @Test
    fun `the whole document parses in a few milliseconds`() {
        val fixture = loadFixture()

        // Warm up once so the assertion measures steady-state parsing, not class loading.
        parseTTML(fixture, includeInterludes = false)

        val started = System.nanoTime()
        repeat(10) { parseTTML(fixture, includeInterludes = false) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        // 10 parses of a 29 KB document. Generous bound: this fails only if the parser becomes
        // accidentally quadratic, which is the realistic regression when touching the stack logic.
        assertTrue("10 parses took ${elapsedMs}ms", elapsedMs < 2_000)
    }
}
