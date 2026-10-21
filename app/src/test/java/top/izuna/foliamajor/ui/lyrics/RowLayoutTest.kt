package top.izuna.foliamajor.ui.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the shared row breaker.
 *
 * The bug this exists to prevent: a single "word" wider than the row used to overflow the canvas,
 * because the old loop only broke *between* items. Long CJK lines are split per character by the
 * parser so they were fine; a long unspaced Latin token was not.
 */
class RowLayoutTest {

    private fun items(vararg widths: Float) = widths.mapIndexed { index, w -> RowLayout.Item(index, w) }

    @Test
    fun `items on one row keep their order and gap`() {
        val result = RowLayout.flow(items(10f, 20f, 30f), maxWidth = 100f, gap = 5f)

        assertEquals(1, result.rows.size)
        // 10 + 20 + 30 plus two 5px gaps.
        assertEquals(70f, result.rows.single().width, 0.01f)
        assertEquals(listOf(0f, 15f, 40f), result.placements.map { it.x })
        assertEquals(listOf(0, 0, 0), result.placements.map { it.row })
    }

    @Test
    fun `an item that would overflow starts a new row`() {
        val result = RowLayout.flow(items(50f, 60f), maxWidth = 100f, gap = 5f)

        assertEquals(2, result.rows.size)
        assertEquals(listOf(0, 1), result.placements.map { it.row })
        assertEquals(0f, result.placements[1].x, 0.01f)
    }

    @Test
    fun `a row is filled as far as it fits`() {
        // 10+5+10+5+10 = 40, adding another 10 needs 55 which is under 60, then 70 > 60.
        val result = RowLayout.flow(items(10f, 10f, 10f, 10f, 10f), maxWidth = 60f, gap = 5f)

        assertEquals(listOf(0, 0, 0, 0, 1), result.placements.map { it.row })
    }

    @Test
    fun `a single item wider than the row overflows rather than being dropped`() {
        // Dropping it would turn a rendering problem into missing lyrics.
        val result = RowLayout.flow(items(200f), maxWidth = 100f, gap = 5f)

        assertEquals(1, result.placements.size)
        assertEquals(200f, result.rows.single().width, 0.01f)
    }

    @Test
    fun `firstOverflowing finds the too-wide item`() {
        val list = items(10f, 20f, 200f, 30f)
        assertEquals(2, RowLayout.firstOverflowing(list, maxWidth = 100f)?.key)
    }

    @Test
    fun `firstOverflowing is null when everything fits`() {
        assertNull(RowLayout.firstOverflowing(items(10f, 20f), maxWidth = 100f))
    }

    @Test
    fun `firstOverflowing treats an exact fit as fine`() {
        assertNull(RowLayout.firstOverflowing(items(100f), maxWidth = 100f))
    }

    @Test
    fun `fitScale shrinks proportionally`() {
        // 120 -> 60 is exactly 0.5 and stays above the 0.55 clamp... it does not: 0.5 < 0.55, so the
        // clamp lifts it. Use a ratio inside the clamp range to test the proportional behaviour.
        assertEquals(0.8f, RowLayout.fitScale(widestWidth = 125f, maxWidth = 100f), 0.001f)
        assertEquals(0.5f, RowLayout.fitScale(widestWidth = 200f, maxWidth = 100f, minScale = 0.3f), 0.001f)
    }

    @Test
    fun `fitScale never enlarges`() {
        assertEquals(1f, RowLayout.fitScale(widestWidth = 50f, maxWidth = 100f), 0.001f)
    }

    @Test
    fun `fitScale respects its lower bound`() {
        // A 1000px word in a 100px row would need 0.1; the clamp keeps the text legible so the
        // caller has to break the word instead.
        assertEquals(0.55f, RowLayout.fitScale(widestWidth = 1000f, maxWidth = 100f), 0.001f)
    }

    @Test
    fun `fitScale handles degenerate input`() {
        assertEquals(1f, RowLayout.fitScale(0f, 100f), 0.001f)
        assertEquals(1f, RowLayout.fitScale(100f, 0f), 0.001f)
    }

    @Test
    fun `empty input produces no rows`() {
        val result = RowLayout.flow(emptyList(), maxWidth = 100f, gap = 5f)
        assertTrue(result.rows.isEmpty())
        assertTrue(result.placements.isEmpty())
    }

    @Test
    fun `zero gap places items flush`() {
        val result = RowLayout.flow(items(10f, 10f), maxWidth = 100f, gap = 0f)
        assertEquals(listOf(0f, 10f), result.placements.map { it.x })
    }

    @Test
    fun `every placement lands inside its row width`() {
        val result = RowLayout.flow(items(30f, 40f, 50f, 20f, 60f, 10f), maxWidth = 100f, gap = 6f)

        val byRow = result.placements.groupBy { it.row }
        for ((rowIndex, placements) in byRow) {
            val row = result.rows[rowIndex]
            for (placement in placements) {
                assertTrue(
                    "item ${placement.key} ends at ${placement.x + placement.width} > ${row.width}",
                    placement.x + placement.width <= row.width + 0.01f,
                )
            }
        }
    }

    @Test
    fun `row widths match the sum of their placements`() {
        val result = RowLayout.flow(items(30f, 40f, 50f), maxWidth = 100f, gap = 6f)

        for (row in result.rows) {
            val inRow = result.placements.filter { it.row == row.index }
            val expected = inRow.sumOf { it.width.toDouble() }.toFloat() + gapFor(inRow.size)
            assertEquals(expected, row.width, 0.01f)
        }
    }

    private fun gapFor(count: Int) = (count - 1).coerceAtLeast(0) * 6f
}
