package top.izuna.foliamajor.lyrics.layout

import kotlin.math.pow

/**
 * Numeric, easing, grapheme and seeding helpers for the `fume` mode.
 *
 * Ported from `src/components/visualizer/fume/fumeMath.ts`. Everything here is pure so the layout and
 * camera below can be tested without a renderer.
 */
object FumeMath {

    /**
     * Splits text into user-perceived characters.
     *
     * The desktop app uses `Intl.Segmenter` and falls back to code points. Kotlin's `String` is UTF-16,
     * so iterating `Char` would split surrogate pairs and render half an emoji; this walks code points
     * and keeps combining marks and ZWJ sequences with their base.
     *
     * This is not a full grapheme cluster algorithm — it handles the cases lyric text actually
     * contains (CJK, Latin, emoji with variation selectors, skin-tone modifiers, and combining marks)
     * rather than the whole Unicode ruleset.
     */
    fun splitGraphemes(text: String): List<String> {
        if (text.isEmpty()) return emptyList()

        val out = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val start = index
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)

            // Attach trailing marks and ZWJ continuations to the same grapheme.
            while (index < text.length) {
                val next = text.codePointAt(index)
                val isCombining = next in 0x0300..0x036F ||
                    next in 0x1AB0..0x1AFF ||
                    next in 0x20D0..0x20FF ||
                    next in 0xFE00..0xFE0F || // variation selectors
                    next in 0x1F3FB..0x1F3FF // skin tone modifiers
                val isJoiner = next == 0x200D
                if (!isCombining && !isJoiner) break

                index += Character.charCount(next)
                // A ZWJ pulls in whatever follows it, so the sequence stays one cluster.
                if (isJoiner && index < text.length) {
                    index += Character.charCount(text.codePointAt(index))
                }
            }
            out.add(text.substring(start, index))
        }
        return out
    }

    fun clamp(value: Float, min: Float, max: Float): Float =
        if (value < min) min else if (value > max) max else value

    fun clamp(value: Double, min: Double, max: Double): Double =
        if (value < min) min else if (value > max) max else value

    fun mix(from: Float, to: Float, amount: Float): Float = from + (to - from) * amount

    fun mix(from: Double, to: Double, amount: Double): Double = from + (to - from) * amount

    fun easeOutCubic(value: Float): Float {
        val clamped = clamp(value, 0f, 1f)
        return 1f - (1f - clamped).pow(3)
    }

    fun easeInCubic(value: Float): Float = clamp(value, 0f, 1f).pow(3)

    fun easeInOutCubic(value: Float): Float {
        val normalized = clamp(value, 0f, 1f)
        return if (normalized < 0.5f) {
            4f * normalized * normalized * normalized
        } else {
            1f - (-2f * normalized + 2f).pow(3) / 2f
        }
    }

    /**
     * Rises to 1 by [peakProgress], then falls back to 0 by the end.
     *
     * Used for the "just printed" glow: it has to fade in with the character and out before the line
     * is superseded, or every finished line stays lit and the page loses its sense of time.
     */
    fun resolveDelayedGlowEnvelope(progress: Float, peakProgress: Float = 0.8f): Float {
        val normalized = clamp(progress, 0f, 1f)
        val peak = clamp(peakProgress, 0.05f, 0.95f)

        return if (normalized <= peak) {
            easeOutCubic(normalized / peak)
        } else {
            1f - easeInCubic((normalized - peak) / (1f - peak))
        }
    }

    private val CJK_REGEX = Regex("[\\u4e00-\\u9fff\\u3040-\\u30ff\\uac00-\\ud7af]")

    fun isCjk(text: String): Boolean = CJK_REGEX.containsMatchIn(text)

    /**
     * FNV-1a, matching the desktop implementation bit for bit.
     *
     * The layout is seeded from this, so a different hash would produce a different page composition
     * for the same song than the desktop app shows.
     */
    fun hashString(input: String): Int {
        var hash = -2128831035 // 2166136261 as a signed Int
        for (char in input) {
            hash = hash xor char.code
            hash *= 16777619
        }
        return hash
    }

    /** A stable 0..1 value from a seed string. */
    fun seeded(seed: String): Float {
        val unsigned = hashString(seed).toLong() and 0xFFFFFFFFL
        return (unsigned % 10000L).toFloat() / 10000f
    }

    /** Quadratic Bézier, used by the camera bridge flight. */
    fun quadraticBezier(from: Float, control: Float, to: Float, amount: Float): Float {
        val normalized = clamp(amount, 0f, 1f)
        val inverse = 1f - normalized
        return inverse * inverse * from + 2f * inverse * normalized * control + normalized * normalized * to
    }
}
