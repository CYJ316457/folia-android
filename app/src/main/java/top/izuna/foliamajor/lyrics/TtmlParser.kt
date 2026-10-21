package top.izuna.foliamajor.lyrics

import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * TTML lyric parser, ported from `@applemusic-like-lyrics/ttml` (AMLL) as used by folia-major.
 *
 * AMLL walks a DOM tree. This walks the same document with a streaming parser and an explicit stack,
 * because pulling in an XML DOM would mean another dependency for a format that is read once per
 * track. The *semantics* are ported exactly — the same time grammar, the same `endsWithSpace` rules,
 * the same "a line's children can correct its declared time" — since those rules are what make
 * Apple/AMLL files line up with the vocal.
 *
 * `KXmlParser` is instantiated directly instead of going through `XmlPullParserFactory`. On Android
 * the factory is provided by the platform, but in plain JVM unit tests it is a stub that throws
 * "not mocked", which would make this — the most format-rule-heavy parser in the app — untestable
 * without an emulator. kxml2 is the same parser Android uses underneath.
 */

private const val NS_TTM = "http://www.w3.org/ns/ttml#metadata"
private const val NS_ITUNES = "http://music.apple.com/lyric-ttml-internal"
private const val NS_XML = "http://www.w3.org/XML/1998/namespace"

/** `[hh:]mm:ss[.fff]`, matching AMLL's TIME_REGEX. `123.4s` is handled separately. */
private val TTML_TIME_REGEX = Regex("""^(?:(?:(?<hours>\d+):)?(?<minutes>\d+):)?(?<seconds>\d+(?:\.\d+)?)$""")
private val MULTI_SPACE_REGEX = Regex("""\s+""")
private val LEADING_SPACE_REGEX = Regex("""^\s""")
private val TRAILING_SPACE_REGEX = Regex("""\s$""")
private val NON_WESTERN_REGEX = Regex("""[\u4e00-\u9fa5\u3040-\u30ff\uac00-\ud7af]""")
private val WESTERN_JOINABLE_REGEX = Regex("""[\p{Script=Latin}\p{N}'’`-]""")

/** A timed syllable before it is grouped into words. */
private data class Syllable(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val endsWithSpace: Boolean = false,
)

private data class SubContent(val language: String?, val text: String)

private data class RawLine(
    val id: String?,
    val agentId: String?,
    val songPart: String?,
    val blockIndex: Int,
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val syllables: List<Syllable>?,
    val translations: List<SubContent>,
    val romanizations: List<SubContent>,
)

/**
 * Accumulator for one `<p>`. Inline translation/romanization spans get their own nested instance so
 * their text never leaks into the sung line.
 */
private class ContentState {
    val text = StringBuilder()
    val syllables = mutableListOf<Syllable>()
    val translations = mutableListOf<SubContent>()
    val romanizations = mutableListOf<SubContent>()
}

/**
 * Parses a TTML document into [LyricData].
 *
 * Returns an empty result for malformed XML rather than throwing: lyric text comes from third-party
 * databases, and one broken file should cost the user that song's lyrics, not the app.
 */
fun parseTTML(ttmlString: String, includeInterludes: Boolean = true): LyricData {
    val lines = mutableListOf<RawLine>()
    var timingMode: String? = null

    try {
        val parser = KXmlParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            // kxml2 rejects a leading XML declaration outright ("PI must not start with xml"), and
            // real TTML files routinely carry one, so it is stripped rather than tolerated.
            setInput(StringReader(stripXmlDeclaration(ttmlString)))
        }

        var blockIndex = 0
        var currentSongPart: String? = null

        // Per-line state, reset when a <p> closes.
        var lineState: ContentState? = null
        var lineId: String? = null
        var lineAgentId: String? = null
        var lineBlockIndex = 0
        var lineBegin = 0L
        var lineEnd = 0L

        // Nested state for an inline translation/romanization span.
        var subState: ContentState? = null
        var subRole: String? = null
        var subLang: String? = null

        // Open plain word <span>s: (startMs, endMs, textLengthWhenOpened). A stack because a word
        // span can nest, and the recorded offset is what lets the span's own text be recovered —
        // a streaming parser appends every text node to the enclosing line, so the span's text is
        // the slice of the line buffer written while it was open.
        val wordSpans = ArrayDeque<Triple<Long, Long, Int>>()

        // True while inside a background-vocal span; its text is consumed and then dropped.
        var insideBackgroundVocal = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name?.substringAfterLast(':')?.lowercase()) {
                        "tt" -> {
                            timingMode = attr(parser, NS_ITUNES, "timing")
                                ?: attr(parser, null, "itunes:timing")
                        }

                        "div" -> {
                            blockIndex += 1
                            currentSongPart = attr(parser, NS_ITUNES, "songPart")
                                ?: attr(parser, NS_ITUNES, "song-part")
                                ?: attr(parser, null, "itunes:songPart")
                        }

                        "p" -> {
                            // Deliberately no blockIndex bump here. AMLL increments it per <div>
                            // (or per body-level <p>), so all lines inside one <div> share the
                            // index — that shared value is what identifies two duet lines as
                            // belonging to the same block.
                            lineState = ContentState()
                            lineId = attr(parser, NS_ITUNES, "key")
                                ?: attr(parser, null, "itunes:key")
                            lineAgentId = attr(parser, NS_TTM, "agent")
                            lineBlockIndex = if (blockIndex == 0) {
                                // A <p> directly under <body> starts its own block.
                                blockIndex = 1
                                blockIndex
                            } else {
                                blockIndex
                            }
                            lineBegin = parseTtmlTime(attr(parser, NS_XML, "begin") ?: attr(parser, null, "begin"))
                            lineEnd = parseTtmlTime(attr(parser, NS_XML, "end") ?: attr(parser, null, "end"))
                        }

                        "span" -> {
                            if (lineState != null) {
                                when (attr(parser, NS_TTM, "role")) {
                                    "x-bg" -> {
                                        // Background vocals are notation, not lyrics. Their text is
                                        // consumed so it cannot leak into the sung line, then dropped:
                                        // the renderer draws one line at a time.
                                        insideBackgroundVocal = true
                                    }

                                    "x-translation", "x-roman" -> {
                                        subState = ContentState()
                                        subRole = if (attr(parser, NS_TTM, "role") == "x-translation") {
                                            "translation"
                                        } else {
                                            "romanization"
                                        }
                                        subLang = attr(parser, NS_XML, "lang")
                                            ?: attr(parser, null, "xml:lang")
                                    }

                                    else -> {
                                        // A plain <span> with begin/end is one word.
                                        val begin = attr(parser, NS_XML, "begin")
                                            ?: attr(parser, null, "begin")
                                        val end = attr(parser, NS_XML, "end")
                                            ?: attr(parser, null, "end")
                                        if (begin != null && end != null) {
                                            val textStart = (subState ?: lineState)?.text?.length ?: 0
                                            wordSpans.addLast(
                                                Triple(parseTtmlTime(begin), parseTtmlTime(end), textStart)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    val raw = parser.text.orEmpty()
                    if (raw.isEmpty() || insideBackgroundVocal) {
                        // nothing
                    } else if (raw.isBlank() && !raw.contains('\n')) {
                        // A whitespace-only node means "space after the previous word".
                        val target = subState ?: lineState
                        if (target != null && target.syllables.isNotEmpty()) {
                            val last = target.syllables.removeAt(target.syllables.lastIndex)
                            target.syllables.add(last.copy(endsWithSpace = true))
                        }
                    } else {
                        val target = subState ?: lineState
                        target?.text?.append(normalizeText(raw, trim = false))
                    }
                }

                XmlPullParser.END_TAG -> {
                    when (parser.name?.substringAfterLast(':')?.lowercase()) {
                        "span" -> when {
                            subState != null && subRole != null -> {
                                val text = normalizeText(subState!!.text.toString())
                                if (text.isNotEmpty()) {
                                    val content = SubContent(language = subLang, text = text)
                                    lineState?.let { state ->
                                        if (subRole == "translation") {
                                            state.translations.add(content)
                                        } else {
                                            state.romanizations.add(content)
                                        }
                                    }
                                }
                                subState = null
                                subRole = null
                                subLang = null
                            }

                            insideBackgroundVocal -> {
                                insideBackgroundVocal = false
                            }

                            wordSpans.isNotEmpty() -> {
                                val (startMs, endMs, textStart) = wordSpans.removeLast()
                                lineState?.let { state ->
                                    val spanText = state.text.substring(textStart.coerceAtMost(state.text.length))
                                    addSyllable(state, spanText, startMs, endMs)
                                }
                            }
                        }

                        "p" -> {
                            lineState?.let { state ->
                                val text = normalizeText(state.text.toString())
                                val (startTime, endTime) = calculateTimeRange(
                                    lineBegin,
                                    lineEnd,
                                    state.syllables,
                                )

                                if (text.isNotEmpty()) {
                                    lines.add(
                                        RawLine(
                                            id = lineId,
                                            agentId = lineAgentId,
                                            songPart = currentSongPart,
                                            blockIndex = lineBlockIndex,
                                            text = text,
                                            startTimeMs = startTime,
                                            endTimeMs = endTime,
                                            syllables = state.syllables.ifEmpty { null },
                                            translations = state.translations,
                                            romanizations = state.romanizations,
                                        )
                                    )
                                }
                            }
                            lineState = null
                            lineId = null
                            lineAgentId = null
                            subState = null
                            subRole = null
                            subLang = null
                            wordSpans.clear()
                        }

                        "div" -> currentSongPart = null
                    }
                }
            }

            event = parser.next()
        }
    } catch (error: Exception) {
        println("[TTML] Failed to parse document: $error")
        return LyricData(lines = emptyList())
    }

    return buildLyricData(lines, timingMode, includeInterludes)
}

/**
 * Appends one word.
 *
 * The word's text is read from the accumulated line text rather than a per-span buffer: a streaming
 * parser hands every text node to the enclosing state, so the span's own text is whatever was
 * appended while it was open.
 */
private fun addSyllable(target: ContentState, rawText: String, startMs: Long, endMs: Long) {
    val normalized = normalizeText(rawText, trim = false)
    if (normalized.isEmpty()) return

    val cleanText = normalized.trim()
    if (cleanText.isEmpty()) return

    if (LEADING_SPACE_REGEX.containsMatchIn(normalized) && target.syllables.isNotEmpty()) {
        val last = target.syllables.removeAt(target.syllables.lastIndex)
        target.syllables.add(last.copy(endsWithSpace = true))
    }

    target.syllables.add(
        Syllable(
            text = cleanText,
            startTimeMs = startMs,
            endTimeMs = endMs,
            endsWithSpace = TRAILING_SPACE_REGEX.containsMatchIn(normalized),
        )
    )
}

private fun attr(parser: XmlPullParser, namespace: String?, name: String): String? =
    parser.getAttributeValue(namespace, name)?.takeIf { it.isNotEmpty() }

/**
 * Drops a UTF-8 BOM and a leading `<?xml ... ?>` declaration.
 *
 * kxml2 treats the declaration as a processing instruction and rejects it, but it is present in
 * almost every real TTML file. The encoding is already known (the string is decoded), so nothing is
 * lost by removing it.
 */
private fun stripXmlDeclaration(source: String): String {
    val withoutBom = source.removePrefix("\uFEFF")
    val trimmedStart = withoutBom.trimStart()
    if (!trimmedStart.startsWith("<?xml")) return withoutBom

    val declarationEnd = trimmedStart.indexOf("?>")
    if (declarationEnd < 0) return withoutBom

    return trimmedStart.substring(declarationEnd + 2).trimStart()
}

/**
 * Reconciles a line's declared time with its children.
 *
 * From AMLL: a missing (0) parent start, or a parent start later than the first child, is replaced
 * by the child's, and the end only ever grows. Apple Music files rely on this — word-timed lines
 * frequently declare no `begin`/`end` at all and expect the syllables to supply them.
 */
private fun calculateTimeRange(
    originalStart: Long,
    originalEnd: Long,
    syllables: List<Syllable>,
): Pair<Long, Long> {
    if (syllables.isEmpty()) {
        return originalStart to originalEnd
    }

    val minChildStart = syllables.minOf { it.startTimeMs }
    val maxChildEnd = syllables.maxOf { it.endTimeMs }

    var startTime = originalStart
    var endTime = originalEnd

    if (startTime == 0L || (minChildStart > 0 && minChildStart < startTime)) {
        startTime = minChildStart
    }
    if (endTime == 0L || maxChildEnd > endTime) {
        endTime = maxChildEnd
    }

    return startTime to endTime
}

/** `[hh:]mm:ss[.fff]` or `12.5s`; unparseable values become 0 rather than throwing. */
internal fun parseTtmlTime(timeStr: String?): Long {
    if (timeStr.isNullOrBlank()) return 0L
    val clean = timeStr.trim()
    if (clean.isEmpty()) return 0L

    if (clean.endsWith("s")) {
        return Math.round((clean.dropLast(1).toDoubleOrNull() ?: return 0L) * 1000)
    }

    val match = TTML_TIME_REGEX.matchEntire(clean) ?: return 0L
    val hours = match.groups["hours"]?.value?.toLongOrNull() ?: 0L
    val minutes = match.groups["minutes"]?.value?.toLongOrNull() ?: 0L
    val seconds = match.groups["seconds"]?.value?.toDoubleOrNull() ?: return 0L

    // Rounded, not truncated: `62.030 * 1000` is 62029.999... in binary floating point, and
    // truncating would shift every millisecond-precision timestamp by one.
    return (hours * 3600 + minutes * 60) * 1000 + Math.round(seconds * 1000)
}

internal fun normalizeText(text: String, trim: Boolean = true): String {
    if (text.isEmpty()) return ""
    val collapsed = MULTI_SPACE_REGEX.replace(text, " ")
    return if (trim) collapsed.trim() else collapsed
}

/**
 * Groups syllables into renderable words.
 *
 * TTML splits Western words into syllables ("Hel" + "lo"), but the visualizer lays out *words* —
 * one box per array entry. Merging only consecutive Latin syllables (never across an
 * `endsWithSpace`) preserves that, while CJK stays one syllable per word so it highlights per
 * character.
 */
private fun buildWordsFromSyllables(
    syllables: List<Syllable>?,
    fallbackText: String,
    startTime: Double,
    endTime: Double,
): List<Word> {
    if (syllables.isNullOrEmpty()) {
        return if (fallbackText.isBlank()) emptyList() else buildTimedWords(fallbackText, startTime, endTime)
    }

    val words = mutableListOf<Word>()
    var group = mutableListOf<Syllable>()

    fun flush() {
        if (group.isEmpty()) return
        val text = group.joinToString("") { it.text }
        if (text.isNotEmpty()) {
            words.add(
                Word(
                    text = text,
                    startTime = group.first().startTimeMs / 1000.0,
                    endTime = group.last().endTimeMs / 1000.0,
                )
            )
        }
        group = mutableListOf()
    }

    syllables.forEachIndexed { index, current ->
        group.add(current)
        val next = syllables.getOrNull(index + 1)
        val shouldMerge = next != null
            && !current.endsWithSpace
            && isWesternJoinable(current.text)
            && isWesternJoinable(next.text)
        if (!shouldMerge) flush()
    }
    flush()

    return words
}

private fun isWesternJoinable(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.isNotEmpty()
        && !NON_WESTERN_REGEX.containsMatchIn(trimmed)
        && WESTERN_JOINABLE_REGEX.containsMatchIn(trimmed)
}

/**
 * Folds overlapping duet lines into the line that is still singing.
 *
 * AMLL/Apple encode simultaneous vocals as overlapping `<p>` elements with different `ttm:agent`
 * values. The renderer shows one line at a time, so without this a duet cuts the first singer off
 * mid-phrase. The harmony content is dropped rather than merged: there is no way to draw two lines
 * at once yet, and silently extending the host's duration would desync display from audio.
 */
private fun foldAgentOverlaps(lines: List<Line>): List<Line> {
    val result = mutableListOf<Line>()

    for (line in lines) {
        val host = result.asReversed().firstOrNull { candidate ->
            candidate.agentId != null
                && line.agentId != null
                && candidate.agentId != line.agentId
                && line.startTime < candidate.endTime
                && line.endTime > candidate.startTime
                && (candidate.blockIndex == null || line.blockIndex == null || candidate.blockIndex == line.blockIndex)
        }

        if (host == null) {
            result.add(line)
        } else {
            result[result.indexOf(host)] = host.copy(isChorus = host.isChorus || line.isChorus)
        }
    }

    return result
}

/**
 * Whether the document is word-timed.
 *
 * AMLL falls back to inference when the root carries no `itunes:timing`: a document is word-timed
 * if any line has more than one syllable. Real AMLL TTML DB files rely on this — the fixture for
 * 晴天 declares no `itunes:timing` at all yet is fully per-character timed.
 */
private fun resolveTimingMode(declared: String?, lines: List<RawLine>): Boolean {
    if (declared != null) return declared == "Word"
    return lines.any { (it.syllables?.size ?: 0) > 1 }
}

private fun buildLyricData(
    rawLines: List<RawLine>,
    timingMode: String?,
    includeInterludes: Boolean,
): LyricData {
    val converted = rawLines.map { raw ->
        val startTime = raw.startTimeMs / 1000.0
        val endTime = raw.endTimeMs / 1000.0

        Line(
            words = buildWordsFromSyllables(raw.syllables, raw.text, startTime, endTime),
            startTime = startTime,
            endTime = endTime,
            fullText = raw.text,
            translation = raw.translations.firstOrNull { it.text.isNotBlank() }?.text,
            romanization = raw.romanizations.firstOrNull { it.text.isNotBlank() }?.text,
            isChorus = raw.songPart?.trim()?.lowercase() == "chorus",
            id = raw.id,
            agentId = raw.agentId,
            songPart = raw.songPart,
            blockIndex = raw.blockIndex,
        )
    }.sortedBy { it.startTime }

    val folded = foldAgentOverlaps(converted)

    return LyricData(
        lines = finalizeParsedLyricLines(folded, includeInterludes),
        // AMLL reports word timing in the document header; a line-timed file has no per-syllable
        // times and its words were spread evenly above.
        isWordByWord = resolveTimingMode(timingMode, rawLines),
    )
}
