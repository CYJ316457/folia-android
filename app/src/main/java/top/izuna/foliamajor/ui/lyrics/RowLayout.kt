package top.izuna.foliamajor.ui.lyrics

/**
 * Row breaking for word-level lyric renderers.
 *
 * Extracted from the Canvas composables so the rule can be tested without a text engine: both
 * `classic` and `still` place words in centred rows, and a wrapping bug there is invisible to the
 * rest of the suite (it only shows up as text running off the side of the screen).
 *
 * The rule is greedy: fill a row until the next item would exceed the width, then start a new one.
 * This mirrors what a text layout engine does for `white-space: normal` text and is what the desktop
 * visualizers get from their canvas 2D context.
 */
object RowLayout {

    /** One item to place, with its measured width. */
    data class Item(val key: Any, val width: Float)

    /** A placed item: which row it landed on and where it starts within that row. */
    data class Placement(val key: Any, val row: Int, val x: Float, val width: Float)

    /** A completed row's total width, for centring. */
    data class Row(val index: Int, val width: Float, val itemCount: Int)

    data class Result(val placements: List<Placement>, val rows: List<Row>)

    /**
     * Flows [items] into rows no wider than [maxWidth].
     *
     * An item wider than a whole row is left overflowing rather than dropped: it is the caller's job
     * to break it first (see [firstOverflowing]). Silently discarding it would turn a rendering
     * problem into missing lyrics.
     */
    fun flow(
        items: List<Item>,
        maxWidth: Float,
        gap: Float,
    ): Result {
        if (items.isEmpty()) return Result(emptyList(), emptyList())

        val placements = mutableListOf<Placement>()
        val rows = mutableListOf<Row>()
        var rowIndex = 0
        var cursorX = 0f
        var countInRow = 0

        for (item in items) {
            val projected = if (countInRow == 0) item.width else cursorX + gap + item.width

            if (countInRow > 0 && projected > maxWidth) {
                rows.add(Row(rowIndex, cursorX, countInRow))
                rowIndex += 1
                cursorX = 0f
                countInRow = 0
            }

            val x = if (countInRow == 0) 0f else cursorX + gap
            placements.add(Placement(item.key, rowIndex, x, item.width))
            cursorX = x + item.width
            countInRow += 1
        }
        rows.add(Row(rowIndex, cursorX, countInRow))

        return Result(placements, rows)
    }

    /**
     * The first item that cannot fit on a row of its own, or null.
     *
     * Callers use this to decide whether to re-measure at a smaller size: a word wider than the
     * available width will overflow no matter how the rows are arranged, so the only fixes are to
     * shrink the type or break inside the word.
     */
    fun firstOverflowing(items: List<Item>, maxWidth: Float): Item? =
        items.firstOrNull { it.width > maxWidth }

    /**
     * The font scale that makes the widest item fit.
     *
     * Widths scale linearly with font size for a given typeface, so one measurement is enough to
     * compute the factor. Clamped to [minScale] so a pathological line cannot shrink the text into
     * illegibility; beyond that point the caller should break the word instead.
     */
    fun fitScale(widestWidth: Float, maxWidth: Float, minScale: Float = 0.55f): Float {
        if (widestWidth <= 0f || maxWidth <= 0f) return 1f
        if (widestWidth <= maxWidth) return 1f
        return (maxWidth / widestWidth).coerceIn(minScale, 1f)
    }
}
