package top.izuna.foliamajor.lyrics.layout

/**
 * Per-grapheme advance widths for one lyric line at a given font size.
 *
 * The desktop fume mode measures text through `@chenglou/pretext`, whose API couples measuring and
 * line breaking: every layout attempt has to re-measure. That is affordable in a browser with a
 * shared cache but not on a phone, where the layout search below tries dozens of column/density
 * combinations for every song.
 *
 * Splitting the two lets the planner measure each line exactly once (at a few font sizes) and do all
 * the wrapping arithmetic itself. It also makes the layout testable without a text engine: a fake
 * measurer that returns a fixed advance per grapheme exercises every branch of the packer.
 */
fun interface FumeGlyphMeasurer {
    /**
     * Advance width of every grapheme of [text] at [fontPx], with no wrapping.
     *
     * Returning fewer entries than there are graphemes is treated as "unknown", and the planner falls
     * back to an estimated advance for the remainder rather than producing a zero-width line.
     */
    fun advances(text: String, fontPx: Float): FloatArray
}

/**
 * A measurer that estimates from character class.
 *
 * Used as the fallback in tests and whenever the real measurer is not available yet. It is
 * deliberately crude — full-width for CJK, 0.55em for Latin — because its only job is to keep the
 * layout finite; the real renderer always supplies measured advances.
 */
object EstimatedFumeMeasurer : FumeGlyphMeasurer {
    override fun advances(text: String, fontPx: Float): FloatArray =
        FumeMath.splitGraphemes(text).map { grapheme ->
            when {
                grapheme.isBlank() -> fontPx * 0.28f
                FumeMath.isCjk(grapheme) -> fontPx
                else -> fontPx * 0.55f
            }
        }.toFloatArray()
}
