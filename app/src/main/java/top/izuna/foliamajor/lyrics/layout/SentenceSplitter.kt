package top.izuna.foliamajor.lyrics.layout

/**
 * Sentence splitting, ported from folia-major's `src/utils/lyrics/sentenceLayout.ts`.
 *
 * The `tilt` visualizer breaks a lyric line into 1–4 fragments so they can be revealed one after
 * another. The split is deliberately **not** a simple character count: breaking mid-phrase produces
 * fragments that read as broken text, so the algorithm tries progressively less semantic boundaries
 * and stops as soon as it has enough fragments:
 *
 *   1. Punctuation (`，。；！？、…` etc.) — always a safe break.
 *   2. Brackets and quotes (`「」『』《》【】` etc.) — keep a quoted phrase together.
 *   3. Western word blocks inside CJK text — a run of Latin words is its own unit.
 *   4. Spaces after CJK — a full-width space is an explicit break the writer chose.
 *   5. Other separators (`：/|~`).
 *
 * If none of those yield enough fragments, the longest candidates are split at a word boundary
 * near their midpoint, which is the least-bad option and is only reached for dense unpunctuated text.
 */
object SentenceSplitter {

    private val CJK_REGEX = Regex("""[\u4e00-\u9fa5\u3040-\u30ff\uac00-\ud7af]""")
    private val PUNCTUATION_REGEX = Regex("""[，。；！？、…·.,;!?]+""")
    private val SPECIAL_CHARS_REGEX = Regex("""[：:/／\\|｜~～]+""")
    private val WESTERN_BLOCK_REGEX = Regex(
        """[a-zA-Z0-9]+(?:[a-zA-Z0-9'\-]*[a-zA-Z0-9]+)?(?:\s+[a-zA-Z0-9]+(?:[a-zA-Z0-9'\-]*[a-zA-Z0-9]+)?)+[.,;:!?。，；：！？]?\s*"""
    )

    private data class PairedSymbol(val open: String, val close: String)

    private val PAIRED_SYMBOLS = listOf(
        PairedSymbol("「", "」"),
        PairedSymbol("『", "』"),
        PairedSymbol("《", "》"),
        PairedSymbol("【", "】"),
        PairedSymbol("｛", "｝"),
        PairedSymbol("［", "］"),
        PairedSymbol("[", "]"),
        PairedSymbol("（", "）"),
        PairedSymbol("(", ")"),
        PairedSymbol("\"", "\""),
        PairedSymbol("'", "'"),
    )

    /**
     * Splits [text] into up to [targetCount] fragments.
     *
     * A `targetCount` of 0 or 1 returns the text unchanged. The result may contain fewer fragments
     * than requested when the text offers no more boundaries — that is preferred over splitting
     * inside a word.
     */
    fun split(text: String, targetCount: Int): List<String> {
        if (targetCount <= 1) return listOf(text)

        var fragments = listOf(text)

        // Levels are tried in order of how safe the boundary is, stopping once there are enough.
        for (level in 1..5) {
            if (fragments.size >= targetCount) break

            // A fragment that is a complete bracketed or quoted span is never split further: the
            // writer put those delimiters there to keep the phrase together, and a later, weaker
            // boundary rule must not override that.
            fragments = fragments.flatMap { fragment ->
                if (isFullyWrapped(fragment)) listOf(fragment) else splitByLevel(fragment, level)
            }
        }

        if (fragments.size < targetCount) {
            fragments = secondarySplit(fragments, targetCount)
        }

        if (fragments.size > targetCount) {
            fragments = mergeToTarget(fragments, targetCount)
        }

        return fragments.filter { it.isNotEmpty() }.ifEmpty { listOf(text) }
    }

    private fun splitByLevel(text: String, level: Int): List<String> = when (level) {
        1 -> splitByPunctuation(text)
        2 -> splitByPairedSymbols(text)
        3 -> splitByWesternBlocks(text)
        4 -> splitByCjkSpace(text)
        5 -> splitBySpecialChars(text)
        else -> listOf(text)
    }

    /**
     * Splits after punctuation, keeping the punctuation and any trailing spaces on the preceding
     * fragment. Leading punctuation with nothing before it is attached to the previous fragment
     * rather than becoming a fragment of its own.
     */
    private fun splitByPunctuation(text: String): List<String> {
        val matches = PUNCTUATION_REGEX.findAll(text).toList()
        if (matches.isEmpty()) return listOf(text)

        val result = mutableListOf<String>()
        var lastIndex = 0

        for (match in matches) {
            val punctStart = match.range.first
            val punctEnd = match.range.last + 1

            val trailingSpaces = Regex("""^\s+""").find(text.substring(punctEnd))?.value.orEmpty()
            val beforePunct = text.substring(lastIndex, punctStart)
            val punctWithSpaces = match.value + trailingSpaces

            when {
                beforePunct.isNotEmpty() -> result.add(beforePunct + punctWithSpaces)
                // Punctuation with nothing before it joins the previous fragment, or stands alone
                // when it opens the line. Matches the desktop app; the merge step below absorbs a
                // lone leading mark into a real fragment.
                result.isNotEmpty() -> result[result.lastIndex] = result.last() + punctWithSpaces
                else -> result.add(punctWithSpaces)
            }

            lastIndex = punctEnd + trailingSpaces.length
        }

        if (lastIndex < text.length) {
            result.add(text.substring(lastIndex))
        }

        // A lone leading mark is not a fragment of its own — attach it to whatever follows.
        if (result.size > 1 && result[0].isNotBlank() && result[0].trim().all { it in "，。；！？、…·.,;!?" }) {
            result[1] = result[0] + result[1]
            result.removeAt(0)
        }

        return result.filter { it.isNotEmpty() }
    }

    /**
     * Whether the whole fragment is one delimited span.
     *
     * Only a fragment that starts with an opener and ends with its matching closer counts; text like
     * `「引用」之后` is not fully wrapped and may still be split after the closer.
     */
    private fun isFullyWrapped(fragment: String): Boolean {
        val trimmed = fragment.trim()
        if (trimmed.length < 2) return false

        return PAIRED_SYMBOLS.any { symbol ->
            trimmed.startsWith(symbol.open) &&
                trimmed.endsWith(symbol.close) &&
                trimmed.length > symbol.open.length + symbol.close.length
        }
    }

    /** Extracts the outermost bracketed or quoted spans, keeping each one intact. */
    private fun splitByPairedSymbols(text: String): List<String> {
        val result = extractOutermost(text)
        return if (result.size > 1) result else listOf(text)
    }

    private fun extractOutermost(text: String): List<String> {
        var best: Triple<Int, Int, Int>? = null // openStart, closeStart, closeEnd

        for (symbol in PAIRED_SYMBOLS) {
            val openIndex = text.indexOf(symbol.open)
            if (openIndex < 0) continue

            val closeIndex = text.indexOf(symbol.close, openIndex + symbol.open.length)
            if (closeIndex < 0) continue

            val candidate = Triple(openIndex, closeIndex, closeIndex + symbol.close.length)
            best = when {
                best == null -> candidate
                candidate.first < best.first -> candidate
                candidate.first == best.first && candidate.third > best.third -> candidate
                else -> best
            }
        }

        val match = best ?: return listOf(text)

        val before = text.substring(0, match.first)
        val paired = text.substring(match.first, match.third)
        val remainder = text.substring(match.third)

        val result = mutableListOf<String>()
        if (before.isNotEmpty()) result.addAll(extractOutermost(before))
        result.add(paired)
        if (remainder.isNotEmpty()) result.addAll(extractOutermost(remainder))

        return result.filter { it.isNotEmpty() }
    }

    /**
     * Splits a multi-word Latin block out of CJK text.
     *
     * Only runs of two or more words count: a single Latin word inside Chinese ("用 iPhone 听") is
     * part of the phrase, not a separate fragment.
     */
    private fun splitByWesternBlocks(text: String): List<String> {
        if (!CJK_REGEX.containsMatchIn(text)) return listOf(text)

        val blocks = WESTERN_BLOCK_REGEX.findAll(text).toList()
        val hasMultiWordBlock = blocks.any { block ->
            Regex("""[a-zA-Z0-9]+""").findAll(block.value).count() > 1
        }
        if (!hasMultiWordBlock) return listOf(text)

        val parts = mutableListOf<String>()
        var lastIndex = 0

        for (block in blocks) {
            val blockStart = block.range.first
            val blockEnd = block.range.last + 1
            val beforeBlock = text.substring(lastIndex, blockStart)

            if (shouldSkipBoundary(beforeBlock, text)) {
                parts.add(beforeBlock + block.value)
            } else if (blockStart > lastIndex) {
                parts.add(beforeBlock)
                parts.add(block.value)
            } else {
                parts.add(block.value)
            }

            lastIndex = blockEnd
        }

        if (lastIndex < text.length) parts.add(text.substring(lastIndex))

        return if (parts.size > 1) parts else listOf(text)
    }

    /**
     * Whether a Latin block should stay attached to what precedes it.
     *
     * A hyphen before the block means it is part of a compound ("Cantopop-風"); a separator that
     * appears more than once in the line is a list separator rather than a phrase break.
     */
    private fun shouldSkipBoundary(beforeBlock: String, fullText: String): Boolean {
        if (beforeBlock.isEmpty()) return false

        val separator = beforeBlock.last()
        if (separator == '-') return true
        if (separator in ":：/／|｜") {
            return fullText.count { it == separator } >= 2
        }
        return false
    }

    /**
     * Splits on spaces that follow CJK.
     *
     * A full-width space is always a break (the writer typed it deliberately). A half-width space
     * only breaks when the preceding character is CJK — a space inside a Latin run is just a space.
     */
    private fun splitByCjkSpace(text: String): List<String> {
        if (!CJK_REGEX.containsMatchIn(text)) return listOf(text)

        val segments = mutableListOf<String>()
        var current = StringBuilder()
        var inCjkBlock = false

        for (character in text) {
            val isCjk = CJK_REGEX.matches(character.toString())
            val isWestern = character.isLetterOrDigit() && !isCjk
            val isSpace = character.isWhitespace()

            when {
                isCjk -> {
                    current.append(character)
                    inCjkBlock = true
                }

                isWestern -> current.append(character)

                isSpace -> {
                    val isFullWidth = character == '\u3000'
                    if (isFullWidth && current.isNotEmpty()) {
                        current.append(character)
                        segments.add(current.toString())
                        current = StringBuilder()
                        inCjkBlock = false
                    } else if (inCjkBlock && current.isNotEmpty()) {
                        val last = current.last()
                        if (CJK_REGEX.matches(last.toString())) {
                            current.append(character)
                            segments.add(current.toString())
                            current = StringBuilder()
                            inCjkBlock = false
                        } else {
                            current.append(character)
                        }
                    } else if (current.isEmpty() && segments.isNotEmpty()) {
                        segments[segments.lastIndex] = segments.last() + character
                    } else {
                        current.append(character)
                    }
                }

                else -> {
                    if (current.isEmpty() && segments.isNotEmpty()) {
                        segments[segments.lastIndex] = segments.last() + character
                    } else {
                        current.append(character)
                    }
                }
            }
        }

        if (current.isNotEmpty()) {
            if (segments.isNotEmpty() && current.all { it.isWhitespace() }) {
                segments[segments.lastIndex] = segments.last() + current
            } else {
                segments.add(current.toString())
            }
        }

        return if (segments.size > 1) segments else listOf(text)
    }

    private fun splitBySpecialChars(text: String): List<String> {
        val parts = text.split(SPECIAL_CHARS_REGEX)
        if (parts.count { it.isNotEmpty() } <= 1) return listOf(text)

        val result = mutableListOf<String>()
        var lastIndex = 0

        for (part in parts) {
            if (part.isEmpty()) continue
            val index = text.indexOf(part, lastIndex)

            if (index > lastIndex) {
                val separator = text.substring(lastIndex, index)
                if (result.isNotEmpty()) {
                    result[result.lastIndex] = result.last() + separator
                } else {
                    result.add(separator)
                }
            }

            result.add(part)
            lastIndex = index + part.length
        }

        if (lastIndex < text.length) {
            val tail = text.substring(lastIndex)
            if (result.isNotEmpty()) {
                result[result.lastIndex] = result.last() + tail
            } else {
                result.add(tail)
            }
        }

        return result.filter { it.isNotEmpty() }
    }

    /**
     * Splits the longest fragment near its midpoint.
     *
     * Reached only for dense text with no usable punctuation. The split point is the character
     * closest to the middle that is not inside a Latin word, so "abcdef" never becomes "abc"+"def"
     * mid-token.
     */
    private fun secondarySplit(fragments: List<String>, targetCount: Int): List<String> {
        val result = fragments.toMutableList()

        while (result.size < targetCount) {
            // A fully delimited span is excluded here too: the punctuation levels above already
            // protected it, and this fallback must not undo that.
            val candidates = result.filter { it.length > 2 && !isFullyWrapped(it) }
            if (candidates.isEmpty()) break

            val longest = candidates.maxByOrNull { it.length } ?: break
            val index = result.indexOf(longest)

            val midpoint = longest.length / 2
            val splitAt = findWordBoundary(longest, midpoint)

            // No usable boundary means the fragment is one indivisible token ("Hello", "晴天").
            // Cutting it in half would produce fragments that read as broken text, which is worse
            // than leaving the line with fewer fragments than requested.
            if (splitAt <= 0 || splitAt >= longest.length) break

            result.removeAt(index)
            result.add(index, longest.substring(0, splitAt))
            result.add(index + 1, longest.substring(splitAt))
        }

        return result
    }

    /**
     * Nearest position to [midpoint] that is not inside a Latin word, or -1 when there is none.
     *
     * Returning -1 is what stops a single token from being cut in half: "Hello" has no position that
     * is not inside the word, so the caller leaves it whole.
     */
    private fun findWordBoundary(text: String, midpoint: Int): Int {
        for (offset in 0 until text.length / 2) {
            val right = midpoint + offset
            if (right in 1 until text.length && isSafeBoundary(text, right)) return right

            val left = midpoint - offset
            if (left in 1 until text.length && isSafeBoundary(text, left)) return left
        }
        return -1
    }

    /**
     * Whether a position is a safe split point.
     *
     * Safe means: not between two Latin word characters. CJK may be split anywhere, which is the
     * whole reason a dense Chinese line can still be fragmented.
     */
    private fun isSafeBoundary(text: String, index: Int): Boolean {
        val before = text.getOrNull(index - 1) ?: return false
        val after = text.getOrNull(index) ?: return false

        val beforeIsLatin = before.isLetterOrDigit() && !CJK_REGEX.matches(before.toString())
        val afterIsLatin = after.isLetterOrDigit() && !CJK_REGEX.matches(after.toString())

        return !(beforeIsLatin && afterIsLatin)
    }

    /** Merges the shortest adjacent pair until only [targetCount] fragments remain. */
    private fun mergeToTarget(fragments: List<String>, targetCount: Int): List<String> {
        val result = fragments.toMutableList()

        while (result.size > targetCount && result.size > 1) {
            var bestIndex = 0
            var shortest = Int.MAX_VALUE

            for (index in 0 until result.size - 1) {
                val combined = result[index].length + result[index + 1].length
                if (combined < shortest) {
                    shortest = combined
                    bestIndex = index
                }
            }

            val merged = result[bestIndex] + result[bestIndex + 1]
            result.removeAt(bestIndex)
            result.removeAt(bestIndex)
            result.add(bestIndex, merged)
        }

        return result
    }
}
