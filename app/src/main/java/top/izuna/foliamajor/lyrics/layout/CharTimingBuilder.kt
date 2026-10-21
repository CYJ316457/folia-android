package top.izuna.foliamajor.lyrics.layout

import top.izuna.foliamajor.lyrics.Line
import top.izuna.foliamajor.lyrics.Word
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Per-character timing for a fragment, ported from `VisualizerTilt.tsx`'s `buildCharTimings`.
 *
 * The tilt visualizer pulses each character as it is sung, which needs a start/end for every
 * non-space character in the fragment. Three sources are tried in order of fidelity:
 *
 *  1. The line's word timings, distributed evenly across each word's characters.
 *  2. A uniform spread over the fragment's own duration, when the words do not line up with the
 *     fragment's character range.
 *
 * The second case exists because a fragment is a *substring* of the line and its boundaries can fall
 * inside a word; rather than guess which half of a word belongs to which fragment, the whole
 * fragment gets an even spread.
 */

/** A character's slice of time. [charIndex] counts non-space characters only. */
data class CharTiming(
    val charIndex: Int,
    val startTime: Double,
    val endTime: Double,
)

object CharTimingBuilder {

    private val WHITESPACE_REGEX = Regex("""^\s+$""")

    /**
     * Builds timings for [segmentText], which starts at [charOffset] in [fullText].
     *
     * Returns an empty list when the line has no words or the fragment has no visible characters.
     */
    fun build(
        charOffset: Int,
        segmentText: String,
        segmentStartTime: Double,
        segmentEndTime: Double,
        line: Line?,
    ): List<CharTiming> {
        if (line == null) return emptyList()

        val graphemes = splitGraphemes(segmentText)
        if (graphemes.isEmpty()) return emptyList()

        val nonSpaceCount = graphemes.count { !WHITESPACE_REGEX.matches(it) }
        if (nonSpaceCount == 0) return emptyList()

        val totalDuration = max(segmentEndTime - segmentStartTime, 0.3)
        val segmentWords = wordsInRange(charOffset, segmentText, line)

        val timings = mutableListOf<CharTiming>()
        var charIndex = 0

        for (word in segmentWords) {
            if (charIndex >= nonSpaceCount) break

            val wordGraphemes = splitGraphemes(word.text).filterNot { WHITESPACE_REGEX.matches(it) }
            if (wordGraphemes.isEmpty()) continue

            val wordDuration = max(word.endTime - word.startTime, 0.05)
            val charDuration = wordDuration / wordGraphemes.size

            for (offsetInWord in wordGraphemes.indices) {
                if (charIndex >= nonSpaceCount) break

                timings.add(
                    CharTiming(
                        charIndex = charIndex,
                        startTime = word.startTime + offsetInWord * charDuration,
                        endTime = word.startTime + (offsetInWord + 1) * charDuration,
                    )
                )
                charIndex += 1
            }
        }

        // The word-derived timings are only usable when they cover exactly the fragment's characters.
        // A mismatch means a fragment boundary fell inside a word, and an even spread is honest about
        // not knowing better.
        if (timings.size != nonSpaceCount) {
            timings.clear()
            val average = totalDuration / nonSpaceCount

            var index = 0
            for (grapheme in graphemes) {
                if (WHITESPACE_REGEX.matches(grapheme)) continue

                timings.add(
                    CharTiming(
                        charIndex = index,
                        startTime = segmentStartTime + index * average,
                        endTime = segmentStartTime + (index + 1) * average,
                    )
                )
                index += 1
            }
        }

        return timings
    }

    /**
     * The words that overlap the fragment's character range.
     *
     * Word texts are located by searching the full text in order rather than assuming they tile it
     * exactly: providers occasionally drop or duplicate whitespace, and an index-based walk would
     * drift after the first mismatch.
     */
    private fun wordsInRange(charOffset: Int, segmentText: String, line: Line): List<Word> {
        val fullText = line.fullText
        if (charOffset < 0 || charOffset >= fullText.length) return line.words

        val segmentEnd = charOffset + segmentText.length
        var searchFrom = 0
        var startIndex = 0
        var endIndex = line.words.size

        line.words.forEachIndexed { index, word ->
            val wordStart = fullText.indexOf(word.text, searchFrom)
            if (wordStart < 0) {
                searchFrom += word.text.length
                return@forEachIndexed
            }

            val wordEnd = wordStart + word.text.length

            if (wordStart <= charOffset && wordEnd > charOffset) {
                startIndex = index
            }
            if (wordStart < segmentEnd && wordEnd >= segmentEnd) {
                endIndex = index + 1
                return@forEachIndexed
            }

            searchFrom = wordEnd
        }

        return line.words.subList(startIndex, endIndex.coerceAtMost(line.words.size))
    }

    /**
     * The pulse multiplier for a character at [currentTime].
     *
     * Rises and falls over the character's own duration (capped at 0.9 s so a long held note does not
     * pulse for its whole length), then decays to a small afterglow rather than snapping to rest —
     * a hard reset makes the line flicker.
     */
    fun pulseIntensity(currentTime: Double, timing: CharTiming): Float {
        val rawDuration = max(timing.endTime - timing.startTime, 0.05)
        val duration = min(max(rawDuration, 0.2), 0.9)
        val elapsed = currentTime - timing.startTime

        if (elapsed < 0) return 0f

        if (elapsed <= duration) {
            return sin(elapsed / duration * PI).toFloat()
        }

        val afterElapsed = elapsed - duration
        val afterglowRamp = duration * 1.2
        if (afterElapsed >= afterglowRamp) return 0.25f

        return (0.25 * (afterElapsed / afterglowRamp)).toFloat()
    }

    /**
     * Splits text into user-perceived characters.
     *
     * `codePoints` is used rather than `chars` so an emoji or a combining mark stays one unit;
     * a `String`'s UTF-16 units would split a surrogate pair into two invisible halves that each
     * received their own pulse.
     */
    private fun splitGraphemes(text: String): List<String> {
        if (text.isEmpty()) return emptyList()

        val result = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val count = Character.charCount(codePoint)
            result.add(text.substring(index, index + count))
            index += count
        }
        return result
    }
}
