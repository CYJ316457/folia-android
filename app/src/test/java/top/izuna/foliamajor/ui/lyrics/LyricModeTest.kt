package top.izuna.foliamajor.ui.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the mode metadata the shared screen reads.
 *
 * The screen used to decide both of these questions by enumerating modes in its own conditions, so a
 * new mode silently inherited the default and drew the shared next-line preview on top of its own
 * layout. The classification lives on the enum now and the compiler forces every new mode to answer
 * it; these tests exist so that flipping an existing answer is a visible edit rather than a quiet
 * regression.
 */
class LyricModeTest {

    @Test
    fun `modes that draw their own surrounding lines suppress the shared preview`() {
        val expected = setOf(LyricMode.STILL, LyricMode.FUME, LyricMode.CAPPELLA, LyricMode.PENDOLO)

        val actual = LyricMode.entries.filter { it.drawsOwnContext }.toSet()

        assertEquals(expected, actual)
    }

    @Test
    fun `single line modes keep the shared preview`() {
        val expected = setOf(
            LyricMode.CLASSIC,
            LyricMode.TILT,
            LyricMode.PARTITA,
            LyricMode.CLADDAGH,
            LyricMode.CADENZA,
        )

        val actual = LyricMode.entries.filterNot { it.drawsOwnContext }.toSet()

        assertEquals(expected, actual)
    }

    @Test
    fun `next cycles through every mode and wraps`() {
        val visited = mutableSetOf<LyricMode>()
        var mode = LyricMode.entries.first()

        repeat(LyricMode.entries.size) {
            visited += mode
            mode = mode.next()
        }

        assertEquals(LyricMode.entries.toSet(), visited)
        assertEquals(LyricMode.entries.first(), mode)
    }

    @Test
    fun `every mode has a distinct label`() {
        val labels = LyricMode.entries.map { it.label }

        assertEquals(labels.size, labels.toSet().size)
        assertTrue(labels.none { it.isBlank() })
    }
}
