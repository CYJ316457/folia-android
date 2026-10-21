package top.izuna.foliamajor.lyrics.layout

import top.izuna.foliamajor.lyrics.Line
import kotlin.math.max
import kotlin.math.min

/** One sung-character boundary: which grapheme, and when it starts and ends. */
data class PendoloGraphemeTiming(
    val startTime: Double,
    val endTime: Double,
)

/**
 * The horizontal fill width of an active lyric line.
 *
 * Ported from `PendoloActiveLyricSweep.tsx`'s `fillWidth` transform. The desktop implements this as
 * a CSS mask driven by a MotionValue; here it is a plain calculation so the timing behaviour can be
 * asserted in unit tests rather than eyeballed in a browser.
 *
 * The fill is measured in pixels along the *wrapped* text, not in characters, because the mask moves
 * across a fixed-width box. That means the caller supplies cumulative grapheme offsets; this object
 * only decides how far along them the playback time has reached.
 */
object PendoloSweepFill {

    /** Result of evaluating the fill: pixels from the start of the line, and whether to show it. */
    data class Fill(val widthPx: Float, val visible: Boolean)

    /**
     * Pixel width of the sung prefix at [currentTime].
     *
     * Three cases matter, and each is a real visual difference:
     *
     *  - Before the first grapheme starts, nothing is filled. Returning the first offset instead
     *    would light up the first character one timing early.
     *  - Inside a grapheme, interpolate across it. Without this the fill jumps character by
     *    character, which on a wide CJK glyph is a visibly chunky step every 200 ms.
     *  - Past the last grapheme, the whole line is filled — even if `currentTime` is beyond
     *    `lineEndTime` (a line can be held on screen past its end).
     *
     * [graphemeOffsetsPx] must be non-decreasing and have one more entry than [timings], so
     * `offsets[i] .. offsets[i + 1]` is the pixel span of grapheme `i`.
     */
    fun widthAt(
        timings: List<PendoloGraphemeTiming>,
        graphemeOffsetsPx: List<Float>,
        lineEndTime: Double,
        currentTime: Double,
        fullWidthPx: Float,
    ): Float {
        if (timings.isEmpty()) {
            return if (currentTime >= lineEndTime) fullWidthPx else 0f
        }

        val firstStart = timings.first().startTime
        if (currentTime <= firstStart) return 0f

        for (index in timings.indices) {
            val timing = timings[index]
            val startWidth = graphemeOffsetsPx.getOrElse(index) { 0f }
            val endWidth = graphemeOffsetsPx.getOrElse(index + 1) { startWidth }

            if (currentTime < timing.startTime) return startWidth
            if (currentTime <= timing.endTime) {
                val span = max(0.001, timing.endTime - timing.startTime)
                return startWidth + (endWidth - startWidth) * ((currentTime - timing.startTime) / span).toFloat()
            }
        }

        return fullWidthPx
    }

    /**
     * Whether the fill layer should be drawn at all.
     *
     * Separate from [widthAt] because the desktop keeps the fill layer mounted and animates its
     * opacity: with the layer hidden, a line whose first grapheme has not started shows its base
     * colour only, which is what keeps the whole line readable before it is sung.
     */
    fun visible(timings: List<PendoloGraphemeTiming>, lineEndTime: Double, currentTime: Double): Boolean {
        val firstStart = timings.firstOrNull()?.startTime ?: lineEndTime
        return currentTime >= firstStart
    }

    /**
     * Builds per-grapheme timings from a line's own word timings.
     *
     * This is the Android port of the desktop's `buildLineGraphemeTimeline`. The desktop also maps
     * word text back onto the displayed string with a grapheme sequence search, because providers
     * emit words that do not tile the full text exactly. That search is reproduced here for the
     * same reason: without it, one missing space shifts every later grapheme's timing.
     */
    fun buildTimings(line: Line): List<PendoloGraphemeTiming> {
        val graphemes = FumeMath.splitGraphemes(line.fullText)
        if (graphemes.isEmpty()) return emptyList()

        if (line.words.isEmpty()) {
            return evenTimings(graphemes.size, line.startTime, line.endTime)
        }

        val out = arrayOfNulls<PendoloGraphemeTiming>(graphemes.size)
        var cursor = 0

        for (word in line.words) {
            val wordGraphemes = FumeMath.splitGraphemes(word.text)
            if (wordGraphemes.isEmpty()) continue

            val matchedStart = findGraphemeSequence(graphemes, wordGraphemes, cursor)
            val start = if (matchedStart >= 0) matchedStart else cursor
            val end = min(start + wordGraphemes.size, graphemes.size)

            // Graphemes between the cursor and this word start belong to no word — typically
            // whitespace or punctuation the provider stripped. They become zero-duration at the
            // word's start so they appear together with it instead of lagging a whole word behind.
            for (gapIndex in cursor until start) {
                out[gapIndex] = PendoloGraphemeTiming(word.startTime, word.startTime)
            }

            val wordTimings = evenTimings(wordGraphemes.size, word.startTime, word.endTime)
            for (localIndex in 0 until (end - start)) {
                out[start + localIndex] = wordTimings.getOrElse(localIndex) {
                    wordTimings.lastOrNull() ?: PendoloGraphemeTiming(word.startTime, word.endTime)
                }
            }

            cursor = max(cursor, end)
        }

        // Trailing graphemes with no matching word: same treatment as the leading gap.
        val fallbackStart = line.words.lastOrNull()?.endTime ?: line.endTime
        for (index in cursor until graphemes.size) {
            if (out[index] == null) {
                out[index] = PendoloGraphemeTiming(fallbackStart, fallbackStart)
            }
        }

        return out.mapIndexed { index, timing ->
            timing ?: evenTimings(graphemes.size, line.startTime, line.endTime).getOrNull(index)
            ?: PendoloGraphemeTiming(line.startTime, line.endTime)
        }
    }

    /** Evenly spreads [count] graphemes across a span, as the desktop's `buildEvenGraphemeTimings`. */
    fun evenTimings(count: Int, startTime: Double, endTime: Double): List<PendoloGraphemeTiming> {
        if (count <= 0) return emptyList()
        val duration = max(endTime - startTime, 0.0)
        val unit = duration / count
        return List(count) { index ->
            PendoloGraphemeTiming(
                startTime = startTime + unit * index,
                endTime = if (index == count - 1) endTime else startTime + unit * (index + 1),
            )
        }
    }

    /**
     * Finds [target] as a contiguous slice of [source] at or after [fromIndex].
     *
     * Returns -1 when absent, which the caller turns into "start where the cursor already is".
     */
    private fun findGraphemeSequence(source: List<String>, target: List<String>, fromIndex: Int): Int {
        if (target.isEmpty()) return fromIndex
        for (index in fromIndex..(source.size - target.size)) {
            var matched = true
            for (targetIndex in target.indices) {
                if (source[index + targetIndex] != target[targetIndex]) {
                    matched = false
                    break
                }
            }
            if (matched) return index
        }
        return -1
    }
}
