package top.izuna.foliamajor.lyrics.layout

import top.izuna.foliamajor.lyrics.Line
import top.izuna.foliamajor.lyrics.getLineRenderEndTime
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * How many graphemes of a line have been "printed" at a given time.
 *
 * Ported from `src/components/visualizer/fume/fumeReveal.ts`. This is the heart of fume: text appears
 * as the vocal reaches it, character by character, rather than fading in as a whole line.
 *
 * The counting is done in graphemes (user-perceived characters) rather than code units, so an emoji
 * or a combining sequence prints as one character instead of two half-printed halves.
 */
object FumeReveal {

    /**
     * The number of printed graphemes.
     *
     * Three cases, in order of fidelity:
     *
     *  1. Before the line starts: nothing.
     *  2. After the line ends: everything, even if word timings were short — a line that is over must
     *     be fully readable.
     *  3. In between: walk the word ranges and print each word's share. Without word ranges, spread
     *     evenly across the line.
     *
     * The `+ 0.2` before flooring in the even case is deliberate and comes from the desktop version:
     * it makes the first character appear as soon as the line starts instead of after a full
     * character's worth of time. Rounding down without it leaves a visible pause at every line.
     */
    fun printedGraphemeCount(
        line: Line,
        wordRanges: List<FumeWordRange>,
        graphemeCount: Int,
        currentTime: Double,
    ): Int {
        if (graphemeCount == 0) return 0
        if (currentTime < line.startTime) return 0
        if (currentTime >= line.endTime) return graphemeCount

        if (wordRanges.isEmpty()) {
            val duration = max(line.endTime - line.startTime, 0.12)
            val progress = FumeMath.clamp(
                (currentTime - line.startTime) / duration,
                0.0,
                1.0,
            ).toFloat()
            return FumeMath.clamp(
                floor(progress * graphemeCount + if (progress > 0) 1 else 0).toFloat(),
                0f,
                graphemeCount.toFloat(),
            ).toInt()
        }

        var printed = 0
        for (range in wordRanges) {
            val partial = printedGraphemesInRange(range, currentTime)
            printed = range.start + partial
            if (partial < range.end - range.start) {
                return printed.coerceIn(0, graphemeCount)
            }
        }
        return printed.coerceIn(0, graphemeCount)
    }

    /**
     * The printed count as a fractional value, for the camera.
     *
     * The camera wants to slide *within* a character as it is being printed, which the integer count
     * cannot express; using the integer would make the camera jump one character at a time.
     */
    fun printedGraphemeProgress(
        line: Line,
        wordRanges: List<FumeWordRange>,
        graphemeCount: Int,
        currentTime: Double,
    ): Float {
        if (graphemeCount == 0) return 0f
        if (currentTime < line.startTime) return 0f
        if (currentTime >= line.endTime) return graphemeCount.toFloat()

        if (wordRanges.isEmpty()) {
            val duration = max(line.endTime - line.startTime, 0.12)
            val progress = FumeMath.clamp(
                (currentTime - line.startTime) / duration,
                0.0,
                1.0,
            ).toFloat()
            return FumeMath.clamp(progress * graphemeCount, 0f, graphemeCount.toFloat())
        }

        var printed = 0f
        for (range in wordRanges) {
            if (currentTime < range.startTime) {
                return FumeMath.clamp(printed, 0f, graphemeCount.toFloat())
            }

            val progress = wordRevealProgress(range, currentTime)
            val length = max(range.end - range.start, 0).toFloat()
            printed = range.start + progress * length

            if (progress < 1f) {
                return FumeMath.clamp(printed, 0f, graphemeCount.toFloat())
            }
        }
        return FumeMath.clamp(printed, 0f, graphemeCount.toFloat())
    }

    private fun wordRevealProgress(range: FumeWordRange, currentTime: Double): Float {
        if (range.endTime <= range.startTime) {
            return if (currentTime >= range.endTime) 1f else 0f
        }

        val duration = max(range.endTime - range.startTime, 0.08)
        return FumeMath.clamp(((currentTime - range.startTime) / duration).toFloat(), 0f, 1f)
    }

    /**
     * Printed graphemes within one word, honouring per-grapheme timings when present.
     *
     * With timings, a word prints on its own schedule (a held note then a quick trill). Without them
     * the whole word prints evenly, but at least one character shows the moment the word starts —
     * otherwise a long word appears to do nothing for its first fraction of a second.
     */
    private fun printedGraphemesInRange(range: FumeWordRange, currentTime: Double): Int {
        val length = max(range.end - range.start, 0)
        if (length == 0) return 0
        if (currentTime < range.startTime) return 0

        val timings = range.graphemeStarts
        if (timings != null && timings.isNotEmpty()) {
            if (currentTime >= range.endTime) return length

            var printed = 0
            for (index in 0 until min(timings.size, length)) {
                if (currentTime >= timings[index]) printed = index + 1
            }
            return printed.coerceIn(0, length)
        }

        val progress = wordRevealProgress(range, currentTime)
        if (progress >= 1f) return length

        return FumeMath.clamp(
            floor(progress * length + 0.2f),
            if (progress > 0f) 1f else 0f,
            length.toFloat(),
        ).toInt()
    }

    /**
     * When a line stops being the current one.
     *
     * Normally its render end, but if the next line starts earlier the line is cut there: two lines
     * revealing at once on the same page would need two cameras.
     */
    fun linePassCutoffTime(line: Line, nextLineStartTime: Double?): Double {
        val renderEnd = getLineRenderEndTime(line)
        if (nextLineStartTime == null || !nextLineStartTime.isFinite()) return renderEnd
        return min(renderEnd, nextLineStartTime)
    }

    /**
     * Progress from [startedAt] to [duration] later, compressed to end by [cutoffTime].
     *
     * Used for the arrival animation of a block: it must finish before the line is superseded even
     * when the line's own duration says otherwise.
     */
    fun progressWithCutoff(
        startedAt: Double,
        duration: Double,
        currentTime: Double,
        cutoffTime: Double,
    ): Float {
        val nominalEnd = startedAt + max(duration, 0.001)
        val effectiveEnd = max(startedAt + 0.001, min(nominalEnd, cutoffTime))
        return FumeMath.clamp(
            ((currentTime - startedAt) / max(effectiveEnd - startedAt, 0.001)).toFloat(),
            0f,
            1f,
        )
    }
}
