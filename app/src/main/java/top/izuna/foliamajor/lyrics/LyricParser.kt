package top.izuna.foliamajor.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.Base64

/**
 * Lyric format parsers, ported from folia-major's `src/utils/lyrics/parserCore.ts`.
 *
 * This file is a deliberate line-by-line port rather than a reimplementation. The desktop parsers
 * encode a lot of hard-won format knowledge — KuGou's relative millisecond offsets, LX Music's
 * verbatim-millisecond awlrc fractions, QRC's ambiguous leading/trailing text split — and none of
 * it is documented anywhere except in those regexes. Reimplementing from the format specs would
 * reproduce the same bugs the desktop app already fixed.
 *
 * TTML is the one format left out: the desktop parser delegates to `@applemusic-like-lyrics/ttml`
 * over a DOM, and porting it means porting an XML tree walk. Every other format here is pure string
 * processing and therefore runs in plain JVM unit tests.
 */

const val INTERLUDE_FULL_TEXT = "......"

data class TimedTextEntry(
    val startTime: Double,
    val endTime: Double? = null,
    val text: String,
)

/** A line whose words are not known yet, before fallback segmentation. */
private data class DraftWord(
    val text: String,
    val startTime: Double,
    val endTime: Double? = null,
)

private data class DraftLine(
    val words: List<DraftWord>,
    val startTime: Double,
    val endTime: Double?,
    val fullText: String,
)

private data class TimestampMarker(
    val time: Double,
    val index: Int,
    val endIndex: Int,
)

private data class LrcMetadata(var title: String? = null, var artist: String? = null)

private data class ParsedTimedEntries(
    val entries: List<TimedTextEntry>,
    val isSorted: Boolean,
)

private val GLOBAL_LRC_TIME_REGEX = Regex("""\[(\d{2}):(\d{2})[.:](\d{2,3})]""")
private val GLOBAL_ANGLE_TIME_REGEX = Regex("""<(\d{2}):(\d{2})[.:](\d{2,3})>""")
private val LRC_LINE_TIME_REGEX = Regex("""^\[(\d{2}):(\d{2})[.:](\d{2,3})]""")
private val LEADING_LRC_TAGS_REGEX = Regex("""^((?:\[(?:\d{2}):(?:\d{2})[.:](?:\d{2,3})])+)(.*)$""")
private val LRC_METADATA_REGEX = Regex("""^\[(ti|ar):([^]]*)]$""", RegexOption.IGNORE_CASE)
private val TTML_ROOT_REGEX = Regex("""<tt(?:\s|>)""", RegexOption.IGNORE_CASE)

// Mirrors LX Music's own `timeExp` (lrc-file-parser): 1..3 colon-separated fields plus a fraction,
// so `[ss.ms]`, `[mm:ss.ms]` and `[hh:mm:ss.ms]` all parse.
private val AWLRC_LINE_REGEX = Regex("""^\[(\d{1,3}(?::\d{1,3}){0,2})\.(\d{1,3})](.*)$""")

// LX normalises KRC's three-field syllable tag down to two fields, but accept the original as well.
private val AWLRC_WORD_REGEX = Regex("""<(\d+),(\d+)(?:,\d+)?>([^<]*)""")

private val CJK_REGEX = Regex("""[\u4e00-\u9fa5\u3040-\u30ff\uac00-\ud7af]""")
private val CJK_PUNCTUATION_REGEX = Regex("""[，。！？、：；"'）]""")
private val WHITESPACE_REGEX = Regex("""\s+""")

/**
 * Distributes one line's duration across its words by weight.
 *
 * CJK text is split per character and punctuation is given zero weight, so a comma does not consume
 * singing time; Latin words are weighted by length. This is the fallback every non-word-timed format
 * goes through, which is why it has to produce plausible timings rather than a uniform split.
 */
internal fun buildTimedWords(text: String, startTime: Double, endTime: Double): List<Word> {
    val duration = (endTime - startTime).coerceAtLeast(0.1)
    val rawTokens = WHITESPACE_REGEX.split(text).filter { it.isNotEmpty() }
    val words = mutableListOf<Word>()
    val tokens = mutableListOf<Pair<String, Double>>()
    var totalWeight = 0.0

    for (token in rawTokens) {
        if (CJK_REGEX.containsMatchIn(token)) {
            token.forEach { char ->
                val isPunctuation = CJK_PUNCTUATION_REGEX.matches(char.toString())
                val weight = if (isPunctuation) 0.0 else 1.0
                tokens.add(char.toString() to weight)
                totalWeight += weight
            }
        } else {
            val weight = 1.0 + (token.length * 0.15)
            tokens.add(token to weight)
            totalWeight += weight
        }
    }

    if (totalWeight == 0.0) {
        totalWeight = 1.0
    }

    val activeDuration = duration * 0.9
    val timePerWeight = activeDuration / totalWeight
    var currentWordStart = startTime

    for ((tokenText, weight) in tokens) {
        val wordDuration = weight * timePerWeight
        val finalDuration = wordDuration.coerceAtLeast(0.05)

        words.add(
            Word(
                text = tokenText,
                startTime = currentWordStart,
                endTime = currentWordStart + finalDuration,
            )
        )

        if (weight > 0) {
            currentWordStart += wordDuration
        } else {
            currentWordStart += 0.05
        }
    }

    // The last word can overrun the line when weights round up; scale the whole set back to fit
    // rather than clamping one word, which would leave an audible gap before the next line.
    val lastWord = words.lastOrNull()
    if (lastWord != null && lastWord.endTime > endTime) {
        val scale = (endTime - startTime) / (lastWord.endTime - startTime)
        for (index in words.indices) {
            val word = words[index]
            words[index] = word.copy(
                startTime = startTime + ((word.startTime - startTime) * scale),
                endTime = startTime + ((word.endTime - startTime) * scale),
            )
        }
    }

    return words
}

fun isInterludeLine(line: Line): Boolean = line.fullText == INTERLUDE_FULL_TEXT

/**
 * Fills gaps longer than 3s with a "......" interlude line, including a lead-in before the first line.
 *
 * The 6 dots are spread across the gap so the interlude animates like any other line instead of
 * sitting static for its whole duration.
 */
fun attachInterludes(lines: List<Line>): List<Line> {
    val finalLines = mutableListOf<Line>()

    fun createInterlude(start: Double, end: Double): Line {
        val duration = end - start
        val wordDuration = duration / 6
        val words = (0 until 6).map { index ->
            Word(
                text = ".",
                startTime = start + (index * wordDuration),
                endTime = start + ((index + 1) * wordDuration),
            )
        }

        return Line(
            startTime = start,
            endTime = end,
            fullText = INTERLUDE_FULL_TEXT,
            words = words,
        )
    }

    if (lines.isNotEmpty() && lines[0].startTime > 3) {
        finalLines.add(createInterlude(0.5, lines[0].startTime - 0.5))
    }

    for (index in lines.indices) {
        val current = lines[index]
        finalLines.add(current)

        val next = lines.getOrNull(index + 1)
        if (next != null) {
            val gap = next.startTime - current.endTime
            if (gap > 3) {
                finalLines.add(createInterlude(current.endTime + 0.05, next.startTime - 0.05))
            }
        }
    }

    return finalLines
}

fun finalizeParsedLyricLines(
    lines: List<Line>,
    includeInterludes: Boolean = true,
): List<Line> {
    val withOptionalInterludes = if (!includeInterludes) lines else attachInterludes(lines)
    return annotateLyricLines(withOptionalInterludes)
}

private fun <T> sortByStartTimeIfNeeded(
    items: List<T>,
    isSorted: Boolean,
    startTime: (T) -> Double,
): List<T> = if (isSorted) items else items.sortedBy(startTime)

private const val ALTERNATE_TRACK_MATCH_WINDOW = 1.0

/** First index whose startTime is >= `time`; `entries` is sorted by startTime. */
private fun lowerBoundByStartTime(entries: List<TimedTextEntry>, time: Double): Int {
    var low = 0
    var high = entries.size
    while (low < high) {
        val middle = (low + high) shr 1
        if (entries[middle].startTime < time) {
            low = middle + 1
        } else {
            high = middle
        }
    }
    return low
}

/**
 * Pairs each lyric line with the translation (or romanization) entry that belongs to it.
 *
 * Every line and every entry is used at most once, and the closest pairs are settled first: all
 * candidate pairs within the ±1s window are ranked by time difference and taken greedily. Exact
 * timestamp matches therefore always win, and a line with no entry of its own does not borrow its
 * neighbour's. The obvious nearest-neighbour lookup lets one entry serve every line within a second
 * of it, so a line sung right after a translated one shows that translation twice, and two lines
 * starting together (a duet) both get the first line's translation.
 */
fun findTranslationsForSortedStartTimes(
    startTimes: List<Double>,
    entries: List<TimedTextEntry>,
): List<String?> {
    val translations = MutableList<String?>(startTimes.size) { null }
    if (startTimes.isEmpty() || entries.isEmpty()) {
        return translations
    }

    data class Candidate(val line: Int, val entry: Int, val diff: Double)

    val candidates = mutableListOf<Candidate>()
    startTimes.forEachIndexed { line, startTime ->
        var entry = lowerBoundByStartTime(entries, startTime - ALTERNATE_TRACK_MATCH_WINDOW)
        while (entry < entries.size && entries[entry].startTime < startTime + ALTERNATE_TRACK_MATCH_WINDOW) {
            val diff = kotlin.math.abs(entries[entry].startTime - startTime)
            if (diff < ALTERNATE_TRACK_MATCH_WINDOW) {
                candidates.add(Candidate(line, entry, diff))
            }
            entry += 1
        }
    }

    // Ties go to the earlier line, then the earlier entry, so equal timestamps pair up in order.
    candidates.sortWith(compareBy({ it.diff }, { it.line }, { it.entry }))

    val lineTaken = BooleanArray(startTimes.size)
    val entryTaken = BooleanArray(entries.size)
    for (candidate in candidates) {
        if (lineTaken[candidate.line] || entryTaken[candidate.entry]) continue
        lineTaken[candidate.line] = true
        entryTaken[candidate.entry] = true
        translations[candidate.line] = entries[candidate.entry].text
    }

    return translations
}

private fun parseTimestamp(minute: String, second: String, fraction: String): Double {
    val min = minute.toInt()
    val sec = second.toInt()
    val ms = "0.$fraction".toDouble()
    return min * 60 + sec + ms
}

private fun collectTimestampMarkers(content: String, pattern: Regex): List<TimestampMarker> =
    pattern.findAll(content).map { match ->
        TimestampMarker(
            time = parseTimestamp(
                match.groupValues[1],
                match.groupValues[2],
                match.groupValues[3],
            ),
            index = match.range.first,
            endIndex = match.range.last + 1,
        )
    }.toList()

private fun parseMetadataLine(line: String, metadata: LrcMetadata): Boolean {
    val match = LRC_METADATA_REGEX.matchEntire(line) ?: return false

    val key = match.groupValues[1].lowercase()
    val value = match.groupValues[2].trim()

    if (key == "ti" && value.isNotEmpty()) {
        metadata.title = value
    } else if (key == "ar" && value.isNotEmpty()) {
        metadata.artist = value
    }

    return true
}

private fun buildPreciseLineDraft(content: String, markers: List<TimestampMarker>): DraftLine? {
    if (markers.size < 2) {
        return null
    }

    val words = mutableListOf<DraftWord>()
    var fullText = ""

    for (index in 0 until markers.size - 1) {
        val current = markers[index]
        val next = markers[index + 1]
        val segment = content.substring(current.endIndex, next.index).replace("\r", "")

        fullText += segment
        if (segment.isBlank()) {
            continue
        }

        words.add(
            DraftWord(
                text = segment,
                startTime = current.time,
                endTime = next.time,
            )
        )
    }

    val trailingText = content.substring(markers.last().endIndex).replace("\r", "")
    fullText += trailingText

    if (trailingText.isNotBlank()) {
        words.add(
            DraftWord(
                text = trailingText,
                startTime = markers.last().time,
            )
        )
    }

    if (fullText.isBlank()) {
        return null
    }

    return DraftLine(
        words = words,
        startTime = words.firstOrNull()?.startTime ?: markers.first().time,
        endTime = words.lastOrNull()?.endTime,
        fullText = fullText,
    )
}

private fun maybeBuildPreciseLineDraft(
    content: String,
    pattern: Regex,
    enabled: Boolean,
): DraftLine? {
    if (!enabled) {
        return null
    }

    return buildPreciseLineDraft(content, collectTimestampMarkers(content, pattern))
}

private fun parseSimpleTimedTextEntry(line: String): TimedTextEntry? {
    val match = LEADING_LRC_TAGS_REGEX.matchEntire(line) ?: return null
    val firstTag = LRC_LINE_TIME_REGEX.find(match.groupValues[1]) ?: return null

    val text = match.groupValues[2].trim()
    if (text.isEmpty()) {
        return null
    }

    return TimedTextEntry(
        startTime = parseTimestamp(
            firstTag.groupValues[1],
            firstTag.groupValues[2],
            firstTag.groupValues[3],
        ),
        text = text,
    )
}

private fun parseTimedTextEntries(content: String): ParsedTimedEntries {
    val metadata = LrcMetadata()
    val entries = mutableListOf<TimedTextEntry>()
    var isSorted = true
    var lastStartTime = Double.NEGATIVE_INFINITY

    val rawLines = content.removePrefix("\uFEFF").split(Regex("""\r?\n"""))

    for (rawLine in rawLines) {
        val line = rawLine.trim()
        if (line.isEmpty()) {
            continue
        }

        if (parseMetadataLine(line, metadata)) {
            continue
        }

        val lineTagMatch = LRC_LINE_TIME_REGEX.find(line)
        val body = if (lineTagMatch != null) line.substring(lineTagMatch.range.last + 1) else line
        val angleDraft = maybeBuildPreciseLineDraft(body, GLOBAL_ANGLE_TIME_REGEX, body.contains('<'))
        val bracketDraft = if (angleDraft != null) {
            null
        } else {
            maybeBuildPreciseLineDraft(line, GLOBAL_LRC_TIME_REGEX, line.indexOf('[', 1) != -1)
        }

        val entry = when {
            angleDraft != null -> TimedTextEntry(
                startTime = angleDraft.startTime,
                endTime = angleDraft.endTime,
                text = angleDraft.fullText,
            )

            bracketDraft != null -> TimedTextEntry(
                startTime = bracketDraft.startTime,
                endTime = bracketDraft.endTime,
                text = bracketDraft.fullText,
            )

            else -> parseSimpleTimedTextEntry(line)
        }

        if (entry == null || entry.text.isEmpty()) {
            continue
        }

        if (entry.startTime < lastStartTime) {
            isSorted = false
        }
        lastStartTime = entry.startTime
        entries.add(entry)
    }

    return ParsedTimedEntries(
        entries = sortByStartTimeIfNeeded(entries, isSorted) { it.startTime },
        isSorted = isSorted,
    )
}

fun parseLRC(
    lrcString: String,
    translationString: String = "",
    includeInterludes: Boolean = true,
    romanizationString: String = "",
): LyricData {
    val lines = mutableListOf<Line>()
    val rawEntries = mutableListOf<TimedTextEntry>()
    var rawEntriesSorted = true
    var lastStartTime = Double.NEGATIVE_INFINITY

    for (rawLine in lrcString.removePrefix("\uFEFF").split(Regex("""\r?\n"""))) {
        val entry = parseSimpleTimedTextEntry(rawLine)
        if (entry == null || entry.text.isEmpty()) {
            continue
        }

        if (entry.startTime < lastStartTime) {
            rawEntriesSorted = false
        }
        lastStartTime = entry.startTime
        rawEntries.add(entry)
    }

    val sortedRawEntries = sortByStartTimeIfNeeded(rawEntries, rawEntriesSorted) { it.startTime }
    val transEntries = parseTimedTextEntries(translationString).entries
    val romanizationEntries = parseTimedTextEntries(romanizationString).entries
    val translations = findTranslationsForSortedStartTimes(sortedRawEntries.map { it.startTime }, transEntries)
    val romanizations = findTranslationsForSortedStartTimes(sortedRawEntries.map { it.startTime }, romanizationEntries)

    for (index in sortedRawEntries.indices) {
        val current = sortedRawEntries[index]
        val next = sortedRawEntries.getOrNull(index + 1)

        var duration = if (next != null) next.startTime - current.startTime else 5.0
        val estimatedReadingTime = current.text.length * 0.5
        if (duration > estimatedReadingTime + 2 && duration > 5) {
            duration = duration.coerceAtMost(estimatedReadingTime + 2)
        }

        val endTime = current.startTime + duration
        lines.add(
            Line(
                words = buildTimedWords(current.text, current.startTime, endTime),
                startTime = current.startTime,
                endTime = endTime,
                fullText = current.text,
                translation = translations[index],
                romanization = romanizations[index],
            )
        )
    }

    return LyricData(
        lines = finalizeParsedLyricLines(lines, includeInterludes),
        isWordByWord = false,
    )
}

/** NetEase YRC: `[lineStartMs,lineDurationMs]` then `(wordStartMs,wordDurationMs,0)text`. */
fun parseYRC(
    yrcString: String,
    translationString: String = "",
    includeInterludes: Boolean = true,
    romanizationString: String = "",
): LyricData {
    data class RawLine(val words: List<Word>, val startTime: Double, val endTime: Double, val fullText: String)

    val rawLinesData = mutableListOf<RawLine>()
    val translationEntries = parseTimedTextEntries(translationString).entries
    val romanizationEntries = parseTimedTextEntries(romanizationString).entries
    var isSorted = true
    var lastStartTime = Double.NEGATIVE_INFINITY

    val lineHeadRegex = Regex("""^\[(\d+),(\d+)](.*)""", RegexOption.DOT_MATCHES_ALL)
    val wordRegex = Regex("""\((\d+),(\d+),(\d+)\)([^(]*)""")

    for (rawLine in yrcString.removePrefix("\uFEFF").split(Regex("""\r?\n"""))) {
        val lineMatch = lineHeadRegex.find(rawLine) ?: continue

        val lineStartTimeMs = lineMatch.groupValues[1].toLong()
        val lineDurationMs = lineMatch.groupValues[2].toLong()
        val rest = lineMatch.groupValues[3]
        val lineStartTime = lineStartTimeMs / 1000.0
        val lineEndTime = (lineStartTimeMs + lineDurationMs) / 1000.0

        val words = mutableListOf<Word>()
        var fullText = ""

        for (wordMatch in wordRegex.findAll(rest)) {
            val wordStartMs = wordMatch.groupValues[1].toLong()
            val wordDurationMs = wordMatch.groupValues[2].toLong()
            val text = wordMatch.groupValues[4]

            words.add(
                Word(
                    text = text,
                    startTime = wordStartMs / 1000.0,
                    endTime = (wordStartMs + wordDurationMs) / 1000.0,
                )
            )
            fullText += text
        }

        if (words.isNotEmpty()) {
            if (lineStartTime < lastStartTime) {
                isSorted = false
            }
            lastStartTime = lineStartTime
            rawLinesData.add(RawLine(words, lineStartTime, lineEndTime, fullText))
        }
    }

    val sortedRawLines = sortByStartTimeIfNeeded(rawLinesData, isSorted) { it.startTime }
    val translations = findTranslationsForSortedStartTimes(sortedRawLines.map { it.startTime }, translationEntries)
    val romanizations = findTranslationsForSortedStartTimes(sortedRawLines.map { it.startTime }, romanizationEntries)

    val lines = sortedRawLines.mapIndexed { index, line ->
        Line(
            words = line.words,
            startTime = line.startTime,
            endTime = line.endTime,
            fullText = line.fullText,
            translation = translations[index],
            romanization = romanizations[index],
        )
    }

    return LyricData(lines = finalizeParsedLyricLines(lines, includeInterludes), isWordByWord = true)
}

/**
 * QQ QRC. Same `[start,duration]` line head as YRC, but the per-word text sits BETWEEN the tags,
 * and the first/last chunk may be unattributed.
 *
 * Which side a chunk belongs to is genuinely ambiguous: the format puts each syllable's text after
 * its tag, but some producers emit it before. The heuristic below resolves it from the whole line's
 * shape (which end is blank) rather than per word, because a single line is written by one producer.
 */
fun parseQRC(
    qrcString: String,
    translationString: String = "",
    includeInterludes: Boolean = true,
    romanizationString: String = "",
): LyricData {
    data class RawLine(val words: List<Word>, val startTime: Double, val endTime: Double, val fullText: String)

    val rawLinesData = mutableListOf<RawLine>()

    fun parseQrcTrackEntries(track: String): List<TimedTextEntry> {
        if (track.isBlank()) return emptyList()

        val qrcLines = parseQRC(track, includeInterludes = false).lines
        if (qrcLines.isNotEmpty()) {
            return qrcLines.map { TimedTextEntry(startTime = it.startTime, text = it.fullText) }
        }

        return parseTimedTextEntries(track).entries
    }

    val translationEntries = parseQrcTrackEntries(translationString)
    val romanizationEntries = parseQrcTrackEntries(romanizationString)
    var isSorted = true
    var lastStartTime = Double.NEGATIVE_INFINITY

    val lineHeadRegex = Regex("""^\[(\d+),(\d+)](.*)""", RegexOption.DOT_MATCHES_ALL)
    val tagRegex = Regex("""\((\d+),(\d+)(?:,\d+)?\)""")

    for (rawLine in qrcString.removePrefix("\uFEFF").split(Regex("""\r?\n"""))) {
        val lineMatch = lineHeadRegex.find(rawLine) ?: continue

        val lineStartTimeMs = lineMatch.groupValues[1].toLong()
        val lineDurationMs = lineMatch.groupValues[2].toLong()
        val rest = lineMatch.groupValues[3]
        val lineStartTime = lineStartTimeMs / 1000.0
        val lineEndTime = (lineStartTimeMs + lineDurationMs) / 1000.0

        data class Tag(val startMs: Long, val durationMs: Long, val tagStart: Int, val tagEnd: Int)

        val tags = tagRegex.findAll(rest).map { match ->
            Tag(
                startMs = match.groupValues[1].toLong(),
                durationMs = match.groupValues[2].toLong(),
                tagStart = match.range.first,
                tagEnd = match.range.last + 1,
            )
        }.toList()

        if (tags.isEmpty()) {
            continue
        }

        val textChunks = mutableListOf<String>()
        var cursor = 0
        for (tag in tags) {
            textChunks.add(rest.substring(cursor, tag.tagStart))
            cursor = tag.tagEnd
        }
        textChunks.add(rest.substring(cursor))

        val prefersLeadingText = textChunks[0].isNotBlank() && textChunks[textChunks.size - 1].isBlank()
        val prefersTrailingText = textChunks[0].isBlank() && textChunks[textChunks.size - 1].isNotBlank()

        val words = mutableListOf<Word>()
        var fullText = ""

        for (index in tags.indices) {
            val tag = tags[index]
            val leadingText = textChunks.getOrElse(index) { "" }
            val trailingText = textChunks.getOrElse(index + 1) { "" }

            val text = when {
                prefersLeadingText -> leadingText
                prefersTrailingText -> trailingText
                trailingText.isNotBlank() && leadingText.isBlank() -> trailingText
                leadingText.isNotBlank() && trailingText.isBlank() -> leadingText
                else -> if (trailingText.length >= leadingText.length) trailingText else leadingText
            }

            if (text.isEmpty()) {
                continue
            }

            words.add(
                Word(
                    text = text,
                    startTime = tag.startMs / 1000.0,
                    endTime = (tag.startMs + tag.durationMs) / 1000.0,
                )
            )
            fullText += text
        }

        if (words.isNotEmpty()) {
            if (lineStartTime < lastStartTime) {
                isSorted = false
            }
            lastStartTime = lineStartTime
            rawLinesData.add(RawLine(words, lineStartTime, lineEndTime, fullText))
        }
    }

    val sortedRawLines = sortByStartTimeIfNeeded(rawLinesData, isSorted) { it.startTime }
    val translations = findTranslationsForSortedStartTimes(sortedRawLines.map { it.startTime }, translationEntries)
    val romanizations = findTranslationsForSortedStartTimes(sortedRawLines.map { it.startTime }, romanizationEntries)

    val lines = sortedRawLines.mapIndexed { index, line ->
        Line(
            words = line.words,
            startTime = line.startTime,
            endTime = line.endTime,
            fullText = line.fullText,
            translation = translations[index],
            romanization = romanizations[index],
        )
    }

    return LyricData(lines = finalizeParsedLyricLines(lines, includeInterludes), isWordByWord = true)
}

private fun parseVttTimestamp(value: String): Double {
    val parts = value.trim().split(':')
    return when (parts.size) {
        3 -> (parts[0].toInt() * 3600) + (parts[1].toInt() * 60) + parts[2].toDouble()
        2 -> (parts[0].toInt() * 60) + parts[1].toDouble()
        else -> parts[0].toDouble()
    }
}

private fun stripVttCueText(text: String): String = text
    .replace(Regex("""<[^>]+>"""), "")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace(Regex("""\s+"""), " ")
    .trim()

private val VTT_TIMING_LINE_REGEX = Regex(
    """^((?:\d{2}:)?\d{2}:\d{2}\.\d{3})\s*-->\s*((?:\d{2}:)?\d{2}:\d{2}\.\d{3})(?:\s+.*)?$"""
)

private fun parseVTTEntries(vttString: String): List<TimedTextEntry> {
    val normalized = vttString.removePrefix("\uFEFF").replace("\r\n", "\n").trim()
    if (normalized.isEmpty()) {
        return emptyList()
    }

    val blocks = normalized.split(Regex("""\n{2,}"""))
    val entries = mutableListOf<TimedTextEntry>()

    for (block in blocks) {
        val lines = block.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

        val first = lines.firstOrNull()
        if (first == null || first == "WEBVTT" || first.startsWith("NOTE") || first == "STYLE" || first == "REGION") {
            continue
        }

        val timingLineIndex = lines.indexOfFirst { VTT_TIMING_LINE_REGEX.matches(it) }
        if (timingLineIndex == -1) {
            continue
        }

        val timingMatch = VTT_TIMING_LINE_REGEX.matchEntire(lines[timingLineIndex]) ?: continue

        val text = stripVttCueText(lines.drop(timingLineIndex + 1).joinToString(" "))
        if (text.isEmpty()) {
            continue
        }

        entries.add(
            TimedTextEntry(
                startTime = parseVttTimestamp(timingMatch.groupValues[1]),
                endTime = parseVttTimestamp(timingMatch.groupValues[2]),
                text = text,
            )
        )
    }

    return entries.sortedBy { it.startTime }
}

fun parseVTT(
    vttString: String,
    translationString: String = "",
    includeInterludes: Boolean = true,
    romanizationString: String = "",
): LyricData {
    val entries = parseVTTEntries(vttString)
    val translationEntries = parseVTTEntries(translationString)
    val romanizationEntries = parseVTTEntries(romanizationString)
    val translations = findTranslationsForSortedStartTimes(entries.map { it.startTime }, translationEntries)
    val romanizations = findTranslationsForSortedStartTimes(entries.map { it.startTime }, romanizationEntries)

    val lines = entries.mapIndexed { index, current ->
        val next = entries.getOrNull(index + 1)
        val fallbackEndTime = if (next != null) next.startTime else current.startTime + 5
        val endTime = (current.endTime ?: fallbackEndTime).coerceAtLeast(current.startTime + 0.1)

        Line(
            words = buildTimedWords(current.text, current.startTime, endTime),
            startTime = current.startTime,
            endTime = endTime,
            fullText = current.text,
            translation = translations[index],
            romanization = romanizations[index],
        )
    }

    return LyricData(lines = finalizeParsedLyricLines(lines, includeInterludes), isWordByWord = false)
}

/**
 * Enhanced LRC: inline `<mm:ss.xx>` word tags, optionally combined with a leading `[mm:ss.xx]` line tag.
 *
 * Lines that carry no inline tags are kept with placeholder words rather than dropped, which is why
 * `isWordByWord` is decided by whether ANY line had real word timing.
 */
fun parseEnhancedLRC(
    lrcString: String,
    translationString: String = "",
    includeInterludes: Boolean = true,
    romanizationString: String = "",
): LyricData {
    val metadata = LrcMetadata()
    val drafts = mutableListOf<DraftLine>()
    val translationEntries = parseTimedTextEntries(translationString).entries
    val romanizationEntries = parseTimedTextEntries(romanizationString).entries
    var isSorted = true
    var lastStartTime = Double.NEGATIVE_INFINITY

    for (rawLine in lrcString.removePrefix("\uFEFF").split(Regex("""\r?\n"""))) {
        val line = rawLine.trim()
        if (line.isEmpty()) {
            continue
        }

        if (parseMetadataLine(line, metadata)) {
            continue
        }

        val lineTagMatch = LRC_LINE_TIME_REGEX.find(line)
        val body = if (lineTagMatch != null) line.substring(lineTagMatch.range.last + 1) else line
        val angleDraft = maybeBuildPreciseLineDraft(body, GLOBAL_ANGLE_TIME_REGEX, body.contains('<'))
        if (angleDraft != null) {
            if (angleDraft.startTime < lastStartTime) {
                isSorted = false
            }
            lastStartTime = angleDraft.startTime
            drafts.add(angleDraft)
            continue
        }

        val bracketDraft = maybeBuildPreciseLineDraft(line, GLOBAL_LRC_TIME_REGEX, line.indexOf('[', 1) != -1)
        if (bracketDraft != null) {
            if (bracketDraft.startTime < lastStartTime) {
                isSorted = false
            }
            lastStartTime = bracketDraft.startTime
            drafts.add(bracketDraft)
            continue
        }

        val simpleEntry = parseSimpleTimedTextEntry(line)
        if (simpleEntry != null) {
            if (simpleEntry.startTime < lastStartTime) {
                isSorted = false
            }
            lastStartTime = simpleEntry.startTime
            drafts.add(
                DraftLine(
                    words = emptyList(),
                    startTime = simpleEntry.startTime,
                    endTime = null,
                    fullText = simpleEntry.text,
                )
            )
        }
    }

    val sortedDrafts = sortByStartTimeIfNeeded(drafts, isSorted) { it.startTime }
    val translations = findTranslationsForSortedStartTimes(sortedDrafts.map { it.startTime }, translationEntries)
    val romanizations = findTranslationsForSortedStartTimes(sortedDrafts.map { it.startTime }, romanizationEntries)

    val lines = sortedDrafts.mapIndexed { index, draft ->
        var lineEndTime = maxOf(
            draft.endTime ?: sortedDrafts.getOrNull(index + 1)?.startTime ?: (draft.startTime + 5),
            draft.startTime + 0.001,
        )

        val words = if (draft.words.isNotEmpty()) {
            draft.words.mapIndexed { wordIndex, word ->
                val nextWordStart = draft.words.getOrNull(wordIndex + 1)?.startTime
                val fallbackEnd = nextWordStart ?: lineEndTime
                val endTime = (word.endTime ?: fallbackEnd).coerceAtLeast(word.startTime + 0.001)
                Word(text = word.text, startTime = word.startTime, endTime = endTime)
            }
        } else {
            buildTimedWords(draft.fullText, draft.startTime, lineEndTime)
        }

        if (words.isNotEmpty()) {
            lineEndTime = lineEndTime.coerceAtLeast(words.last().endTime)
        }

        Line(
            words = words,
            startTime = draft.startTime,
            endTime = lineEndTime,
            fullText = draft.fullText,
            translation = translations[index],
            romanization = romanizations[index],
        )
    }

    return LyricData(
        lines = finalizeParsedLyricLines(lines, includeInterludes),
        title = metadata.title,
        artist = metadata.artist,
        // Enhanced LRC may mix timed and plain lines; any line that carried its own word
        // timestamps makes the file word-timed. Plain lines got evenly spread placeholder words.
        isWordByWord = sortedDrafts.any { it.words.isNotEmpty() },
    )
}

/**
 * Kugou KRC. Structured like QRC/YRC but uses angle-bracket word tags and millisecond offsets
 * RELATIVE to the line start (QRC/YRC are absolute), which is the single easiest thing to get wrong.
 *
 * The `[language:...]` tag carries a base64 JSON blob with embedded romanization (type 0) and
 * translation (type 1) tracks; when present those win over separately-fetched tracks.
 */
fun parseKRC(
    krcString: String,
    translationString: String = "",
    includeInterludes: Boolean = true,
    romanizationString: String = "",
): LyricData {
    data class RawLine(val words: List<Word>, val startTime: Double, val endTime: Double, val fullText: String)

    val rawLinesData = mutableListOf<RawLine>()

    var embeddedTranslations = emptyList<String>()
    var embeddedRomanizations = emptyList<String>()

    Regex("""\[language:([^]]*)]""").find(krcString)?.let { langMatch ->
        runCatching {
            var cleanB64 = langMatch.groupValues[1].trim()
            while (cleanB64.length % 4 != 0) {
                cleanB64 += "="
            }
            val decoded = String(Base64.getDecoder().decode(cleanB64), Charsets.UTF_8)
            val root = Json.parseToJsonElement(decoded).jsonObject
            val content = root["content"] as? JsonArray ?: JsonArray(emptyList())

            fun readEmbeddedTrack(type: Int): List<String> {
                val track = content.mapNotNull { it as? JsonObject }
                    .firstOrNull { (it["type"] as? JsonPrimitive)?.content?.toIntOrNull() == type }
                    ?: return emptyList()
                val lyricContent = track["lyricContent"] as? JsonArray ?: return emptyList()

                return lyricContent.map { entry ->
                    when (entry) {
                        is JsonArray -> entry.joinToString("") { (it as? JsonPrimitive)?.content ?: "" }.trim()
                        is JsonPrimitive -> entry.content.trim()
                        else -> ""
                    }
                }
            }

            embeddedRomanizations = readEmbeddedTrack(0)
            embeddedTranslations = readEmbeddedTrack(1)
        }.onFailure { error ->
            println("[Kugou KRC] Failed to decode/parse language tag: $error")
        }
    }

    val translationEntries = parseTimedTextEntries(translationString).entries
    val romanizationEntries = parseTimedTextEntries(romanizationString).entries
    var isSorted = true
    var lastStartTime = Double.NEGATIVE_INFINITY

    val lineHeadRegex = Regex("""^\[(\d+),(\d+)](.*)""", RegexOption.DOT_MATCHES_ALL)
    val wordRegex = Regex("""<(\d+),(\d+)(?:,\d+)?>([^<]*)""")

    for (rawLine in krcString.removePrefix("\uFEFF").split(Regex("""\r?\n"""))) {
        val lineMatch = lineHeadRegex.find(rawLine) ?: continue

        val lineStartTimeMs = lineMatch.groupValues[1].toLong()
        val lineDurationMs = lineMatch.groupValues[2].toLong()
        val rest = lineMatch.groupValues[3]
        val lineStartTime = lineStartTimeMs / 1000.0
        val lineEndTime = (lineStartTimeMs + lineDurationMs) / 1000.0

        val words = mutableListOf<Word>()
        var fullText = ""

        for (wordMatch in wordRegex.findAll(rest)) {
            val wordStartOffsetMs = wordMatch.groupValues[1].toLong()
            val wordDurationMs = wordMatch.groupValues[2].toLong()
            val text = wordMatch.groupValues[3]

            // Note: KRC word start offset is relative to the line start time.
            val wordStartMs = lineStartTimeMs + wordStartOffsetMs

            words.add(
                Word(
                    text = text,
                    startTime = wordStartMs / 1000.0,
                    endTime = (wordStartMs + wordDurationMs) / 1000.0,
                )
            )
            fullText += text
        }

        // If no word tags but text is present, build timed words
        val trimmedRest = rest.trim()
        if (words.isEmpty() && trimmedRest.isNotEmpty() && !trimmedRest.startsWith("[") && !trimmedRest.startsWith("<")) {
            words.addAll(buildTimedWords(trimmedRest, lineStartTime, lineEndTime))
            fullText = trimmedRest
        }

        if (words.isNotEmpty()) {
            if (lineStartTime < lastStartTime) {
                isSorted = false
            }
            lastStartTime = lineStartTime
            rawLinesData.add(RawLine(words, lineStartTime, lineEndTime, fullText))
        }
    }

    val sortedRawLines = sortByStartTimeIfNeeded(rawLinesData, isSorted) { it.startTime }
    val lines = sortedRawLines.mapIndexed { index, line ->
        var translation = embeddedTranslations.getOrNull(index)?.takeIf { it.isNotEmpty() }
        var romanization = embeddedRomanizations.getOrNull(index)?.takeIf { it.isNotEmpty() }

        if (translation == null && translationEntries.isNotEmpty()) {
            translation = findTranslationsForSortedStartTimes(listOf(line.startTime), translationEntries)[0]
        }
        if (romanization == null && romanizationEntries.isNotEmpty()) {
            romanization = findTranslationsForSortedStartTimes(listOf(line.startTime), romanizationEntries)[0]
        }

        Line(
            words = line.words,
            startTime = line.startTime,
            endTime = line.endTime,
            fullText = line.fullText,
            translation = translation,
            romanization = romanization,
        )
    }

    return LyricData(lines = finalizeParsedLyricLines(lines, includeInterludes), isWordByWord = true)
}

/**
 * Converts an awlrc line head to seconds.
 *
 * LX Music builds these stamps as `${mm}:${ss}.${ms}` where `ms` is the raw `time % 1000` remainder,
 * and its KuGou and Migu adapters emit it without zero padding (only the QQ adapter pads to three
 * digits). So the fraction is a verbatim millisecond count, not a decimal fraction: `[02:43.97]` is
 * 43s 97ms, not 43s 970ms. Reading it as an LRC centisecond fraction shifts those lines by up to
 * 900ms and pushes them past the following line.
 */
private fun parseAwlrcTimestamp(fields: String, fraction: String): Double {
    val parts = fields.split(':').map { it.toInt() }.toMutableList()
    while (parts.size < 3) {
        parts.add(0, 0)
    }

    val (hours, minutes, seconds) = parts
    return (hours * 3600) + (minutes * 60) + seconds + (fraction.toInt() / 1000.0)
}

/** Reads a plain (non word-by-word) container track using awlrc timestamp semantics. */
private fun parseAwlrcAlternateTrack(content: String): List<TimedTextEntry> {
    val entries = mutableListOf<TimedTextEntry>()

    for (rawLine in content.removePrefix("\uFEFF").split(Regex("""\r?\n"""))) {
        val match = AWLRC_LINE_REGEX.matchEntire(rawLine.trim()) ?: continue
        val text = match.groupValues[3].trim()
        if (text.isEmpty()) {
            continue
        }

        entries.add(TimedTextEntry(startTime = parseAwlrcTimestamp(match.groupValues[1], match.groupValues[2]), text = text))
    }

    return entries
}

/**
 * Resolves an alternate track against line start times.
 *
 * KuGou's container tracks are timestamp-identical to the awlrc track, so a single exact hit proves
 * the track is timestamp-keyed; from there every lookup stays exact. A line with no exact counterpart
 * genuinely has no alternate text (title and composer credits are blank rows in those tracks), and
 * falling back to the ±1s nearest-neighbour match there would hand it the next line's translation.
 * Only a track with zero exact hits — a differently-timed source — uses the nearest-neighbour match.
 */
private fun resolveAlternateTracks(
    startTimes: List<Double>,
    entries: List<TimedTextEntry>,
): List<String?> {
    if (entries.isEmpty()) {
        return startTimes.map { null }
    }

    val exactByMs = mutableMapOf<Long, String>()
    for (entry in entries) {
        val key = Math.round(entry.startTime * 1000)
        if (!exactByMs.containsKey(key)) {
            exactByMs[key] = entry.text
        }
    }

    val exact = startTimes.map { exactByMs[Math.round(it * 1000)] }
    if (exact.any { it != null }) {
        return exact
    }

    return findTranslationsForSortedStartTimes(startTimes, entries)
}

/**
 * Parses the word-by-word track of a KuGou/LX `[awlrc:...]` container.
 * Unlike KRC, the line head carries only a start timestamp — the line end is derived from the last
 * syllable, whose `<offsetMs,durationMs>` marker is relative to the line start.
 */
fun parseAwlrc(
    awlrcString: String,
    translationString: String = "",
    includeInterludes: Boolean = true,
    romanizationString: String = "",
): LyricData {
    val metadata = LrcMetadata()
    data class Draft(val words: List<Word>, val startTime: Double, val endTime: Double, val fullText: String)

    val drafts = mutableListOf<Draft>()

    for (rawLine in awlrcString.removePrefix("\uFEFF").split(Regex("""\r?\n"""))) {
        val line = rawLine.trim()
        if (line.isEmpty() || parseMetadataLine(line, metadata)) {
            continue
        }

        val lineMatch = AWLRC_LINE_REGEX.matchEntire(line) ?: continue

        val startTime = parseAwlrcTimestamp(lineMatch.groupValues[1], lineMatch.groupValues[2])
        val words = mutableListOf<Word>()
        var fullText = ""
        // Cursor repairs the zero-duration and backwards syllable markers KuGou occasionally emits.
        var cursorMs = 0L

        for (wordMatch in AWLRC_WORD_REGEX.findAll(lineMatch.groupValues[3])) {
            val offsetMs = maxOf(wordMatch.groupValues[1].toLong(), cursorMs)
            val durationMs = maxOf(wordMatch.groupValues[2].toLong(), 1L)

            words.add(
                Word(
                    text = wordMatch.groupValues[3],
                    startTime = startTime + (offsetMs / 1000.0),
                    endTime = startTime + ((offsetMs + durationMs) / 1000.0),
                )
            )
            cursorMs = offsetMs + durationMs
            fullText += wordMatch.groupValues[3]
        }

        if (words.isEmpty() || fullText.isBlank()) {
            continue
        }

        drafts.add(Draft(words, startTime, startTime + (cursorMs / 1000.0), fullText))
    }

    // Some exports order the credit lines by fraction digits rather than by value (`.79` before `.181`).
    drafts.sortBy { it.startTime }

    // The container's tracks are all stamped by the same LX writer, so they must be read with the
    // same timestamp semantics as the awlrc track or the exact-match alignment below falls apart.
    val startTimes = drafts.map { it.startTime }
    val translations = resolveAlternateTracks(startTimes, parseAwlrcAlternateTrack(translationString))
    val romanizations = resolveAlternateTracks(startTimes, parseAwlrcAlternateTrack(romanizationString))

    val lines = drafts.mapIndexed { index, draft ->
        val next = drafts.getOrNull(index + 1)
        // The derived end may overrun the next line when the final syllable is padded.
        val endTime = if (next != null) minOf(draft.endTime, next.startTime) else draft.endTime

        Line(
            words = draft.words,
            startTime = draft.startTime,
            endTime = endTime.coerceAtLeast(draft.startTime),
            fullText = draft.fullText,
            translation = translations[index],
            romanization = romanizations[index],
        )
    }

    return LyricData(
        lines = finalizeParsedLyricLines(lines, includeInterludes),
        title = metadata.title,
        artist = metadata.artist,
        isWordByWord = true,
    )
}

enum class LyricParseFormat {
    LRC,
    ENHANCED_LRC,
    VTT,
    YRC,
    QRC,
    KRC,
    AWLRC,
    TTML,
    ;

    companion object {
        /**
         * Sniffs the format from content, mirroring `formatDetection.ts`.
         *
         * Order matters: TTML before VTT (both are XML-ish text), and enhanced LRC last because its
         * markers are the easiest to find accidentally in ordinary lyric text.
         */
        fun detect(content: String?): LyricParseFormat {
            val normalized = content?.removePrefix("\uFEFF")?.trim().orEmpty()

            // TTML is checked first. Its document contains no `-->` or `[mm:ss]`, but a lyric line
            // inside it could coincidentally look like one, and misreading a whole TTML file as VTT
            // yields zero lines rather than a degraded result.
            if (TTML_ROOT_REGEX.containsMatchIn(normalized) && (
                    normalized.contains("http://www.w3.org/ns/ttml")
                        || normalized.contains("itunes:timing=")
                        || normalized.contains("ttm:agent=")
                        || normalized.contains("ttm:role=")
                    )
            ) {
                return TTML
            }

            if (normalized.startsWith("WEBVTT") || Regex("""(?:^|\n)\s*(?:\d{2}:)?\d{2}:\d{2}\.\d{3}\s*-->\s*(?:\d{2}:)?\d{2}:\d{2}\.\d{3}""").containsMatchIn(normalized)) {
                return VTT
            }

            if (Regex("""<\d{2}:\d{2}[.:]\d{2,3}>""").containsMatchIn(normalized)) {
                return ENHANCED_LRC
            }

            return LRC
        }

        /** Extension-based hint, for local files where the name is more reliable than the content. */
        fun fromFileName(fileName: String?): LyricParseFormat? =
            when (fileName?.substringAfterLast('.', "")?.lowercase()) {
                "vtt" -> VTT
                "ttml" -> TTML
                "qrc" -> QRC
                "yrc" -> YRC
                "krc" -> KRC
                else -> null
            }
    }
}

/** Dispatcher matching `parseLyricsByFormat`. TTML is not ported yet — see the file header. */
fun parseLyricsByFormat(
    format: LyricParseFormat,
    content: String,
    translation: String = "",
    includeInterludes: Boolean = true,
    romanization: String = "",
): LyricData = when (format) {
    LyricParseFormat.YRC -> parseYRC(content, translation, includeInterludes, romanization)
    LyricParseFormat.QRC -> parseQRC(content, translation, includeInterludes, romanization)
    LyricParseFormat.KRC -> parseKRC(content, translation, includeInterludes, romanization)
    LyricParseFormat.AWLRC -> parseAwlrc(content, translation, includeInterludes, romanization)
    LyricParseFormat.ENHANCED_LRC -> parseEnhancedLRC(content, translation, includeInterludes, romanization)
    LyricParseFormat.VTT -> parseVTT(content, translation, includeInterludes, romanization)
    LyricParseFormat.LRC -> parseLRC(content, translation, includeInterludes, romanization)
    LyricParseFormat.TTML -> parseTTML(content, includeInterludes)
}
