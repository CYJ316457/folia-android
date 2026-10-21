package top.izuna.foliamajor.lyrics.layout

import top.izuna.foliamajor.lyrics.Line
import kotlin.math.max
import kotlin.math.min

/**
 * Per-character reveal timing for `cappella` bubbles.
 *
 * Ported from `src/components/visualizer/cappella/cappellaReveal.ts`. The visual text is rendered one
 * character at a time, but the parser only reports word timings, so this bridges the two.
 *
 * Three things depend on the same table:
 *
 *  - which characters have appeared (drives the fade),
 *  - how wide the bubble should be (driven by a *look-ahead* copy of the times, so the bubble grows
 *    slightly before the character lands — without that the character briefly wraps to the next
 *    line while the bubble is still narrow),
 *  - when the timestamp appears (after the last character has finished fading in).
 */
data class CappellaRevealPlan(
    val characters: List<String>,
    /** Start time of each character's fade. */
    val revealTimes: List<Double>,
    /** Fade duration in milliseconds, per character. */
    val fadeDurationsMs: List<Int>,
    /** When the timestamp may appear: the last fade has completed. */
    val timestampReadyTime: Double,
)

object CappellaReveal {

    /** Bubble width animates over about 0.2s, so its timeline leads the text by that much. */
    const val WIDTH_LOOKAHEAD_SECONDS = 0.2

    const val DEFAULT_CHAR_FADE_MS = 220
    const val MIN_CHAR_FADE_MS = 40

    fun plan(line: Line): CappellaRevealPlan {
        val characters = FumeMath.splitGraphemes(line.fullText)
        val revealTimes = buildRevealTimes(line, characters)
        val fadeDurations = buildFadeDurations(line, characters)

        var ready = line.startTime
        for (index in revealTimes.indices) {
            val time = revealTimes[index]
            if (!time.isFinite()) continue
            ready = max(ready, time + fadeDurations[index] / 1000.0)
        }

        return CappellaRevealPlan(
            characters = characters,
            revealTimes = revealTimes,
            fadeDurationsMs = fadeDurations,
            timestampReadyTime = ready,
        )
    }

    /**
     * The bubble-width timeline: the reveal times shifted earlier by the look-ahead.
     *
     * Clamped at the line's start so a bubble never begins growing before the line exists.
     */
    fun bubbleTargetTimes(plan: CappellaRevealPlan, line: Line): List<Double> =
        plan.revealTimes.map { time -> max(line.startTime, time - WIDTH_LOOKAHEAD_SECONDS) }

    /**
     * How many characters are visible at [currentTime].
     *
     * `revealTimes` is monotonic by construction, so a binary search is valid — and it is what makes
     * this cheap enough to call per character per frame.
     */
    fun visibleCount(revealTimes: List<Double>, currentTime: Double): Int {
        var low = 0
        var high = revealTimes.size
        while (low < high) {
            val mid = (low + high) / 2
            if (revealTimes[mid] <= currentTime) low = mid + 1 else high = mid
        }
        return low
    }

    /**
     * Assigns a start time to every character.
     *
     * The desktop version first tries a grapheme timeline built from the whole line and falls back to
     * mapping word ranges onto character offsets. Only the fallback is needed here: the Android lyric
     * model does not carry a line-level grapheme timeline, so the word ranges are always the source.
     *
     * Two details matter for the visible result:
     *
     *  - characters between two timed words (spaces, sticky punctuation) take the *next* word's start,
     *    so the visible text stays contiguous instead of leaving a gap that then fills in;
     *  - any trailing characters take the last resolved time rather than `line.endTime`, otherwise the
     *    timestamp waits for the next line to start.
     */
    internal fun buildRevealTimes(line: Line, characters: List<String>): List<Double> {
        val reveal = MutableList(characters.size) { Double.POSITIVE_INFINITY }
        if (characters.isEmpty()) return reveal

        var searchCursor = 0
        var previousEndIndex = 0
        var lastResolved = line.startTime
        var hasResolved = false

        for (word in line.words) {
            val startOffset = line.fullText.indexOf(word.text, searchCursor)
            if (startOffset < 0) continue

            val endOffset = startOffset + word.text.length
            val startIndex = graphemeCountOf(line.fullText, startOffset)
            val endIndex = graphemeCountOf(line.fullText, endOffset)

            // Characters before this word (usually spaces) reveal with it.
            for (index in previousEndIndex until min(startIndex, reveal.size)) {
                reveal[index] = word.startTime
            }

            val wordGraphemes = FumeMath.splitGraphemes(word.text)
            val duration = word.endTime - word.startTime
            wordGraphemes.forEachIndexed { characterIndex, _ ->
                val target = startIndex + characterIndex
                if (target >= reveal.size) return@forEachIndexed

                // Even spread across the word: the Android model has no per-syllable timing.
                val time = if (wordGraphemes.isEmpty()) {
                    word.startTime
                } else {
                    word.startTime + duration * characterIndex / wordGraphemes.size
                }
                reveal[target] = time
                lastResolved = max(lastResolved, time)
                hasResolved = true
            }

            previousEndIndex = endIndex
            searchCursor = endOffset
        }

        val trailing = if (hasResolved) lastResolved else line.endTime
        for (index in previousEndIndex until reveal.size) {
            reveal[index] = trailing
        }
        // Anything still unresolved sits before the first timed word; use the line start.
        for (index in reveal.indices) {
            if (!reveal[index].isFinite()) reveal[index] = line.startTime
        }

        return reveal
    }

    /**
     * Fade duration per character, taken from the gap the word gives it.
     *
     * A plain 220ms for every character made fast passages look like a slideshow: the text kept
     * fading in after the singer had moved on. Using the actual gap keeps the fade proportional to
     * how long the character is held, with a floor so a very fast syllable still fades rather than
     * popping.
     */
    internal fun buildFadeDurations(line: Line, characters: List<String>): List<Int> {
        val durations = MutableList(characters.size) { DEFAULT_CHAR_FADE_MS }
        if (characters.isEmpty()) return durations

        var searchCursor = 0
        for (word in line.words) {
            val startOffset = line.fullText.indexOf(word.text, searchCursor)
            if (startOffset < 0) continue

            val startIndex = graphemeCountOf(line.fullText, startOffset)
            val wordGraphemes = FumeMath.splitGraphemes(word.text)
            val duration = word.endTime - word.startTime
            val perCharacter = if (wordGraphemes.isEmpty()) 0.0 else duration / wordGraphemes.size

            wordGraphemes.indices.forEach { characterIndex ->
                val target = startIndex + characterIndex
                if (target >= durations.size) return@forEach
                durations[target] = max(
                    (perCharacter * 1000).toInt(),
                    MIN_CHAR_FADE_MS,
                )
            }

            searchCursor = startOffset + word.text.length
        }

        return durations
    }

    /** Number of graphemes in the first [offset] UTF-16 units of [text]. */
    private fun graphemeCountOf(text: String, offset: Int): Int =
        FumeMath.splitGraphemes(text.substring(0, offset.coerceIn(0, text.length))).size
}
