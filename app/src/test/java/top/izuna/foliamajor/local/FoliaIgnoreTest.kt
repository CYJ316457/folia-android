package top.izuna.foliamajor.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for `.foliaignore`.
 *
 * The rules that decide whether a library imports the right files: later rules override earlier
 * ones, a directory rule covers everything beneath it, and a nested file only overrides paths its
 * own rules mention.
 */
class FoliaIgnoreTest {

    private fun matcher(contents: String, base: String = "") =
        FoliaIgnoreMatcher.parse(contents, base)

    // --- Comments and blanks ---------------------------------------------------------------------

    @Test
    fun `comments and blank lines are ignored`() {
        val m = matcher(
            """
            # a comment

            *.tmp.mp3
            """.trimIndent()
        )

        assertEquals(1, m.ruleCount)
    }

    @Test
    fun `an escaped hash is a literal name`() {
        val m = matcher("""\#notes.mp3""")

        assertTrue(m.isIgnored("#notes.mp3", isDirectory = false))
    }

    @Test
    fun `an escaped bang is a literal name`() {
        val m = matcher("""\!demo.mp3""")

        assertTrue(m.isIgnored("!demo.mp3", isDirectory = false))
        // The escape must not turn it into a negation rule.
        assertFalse(m.isIgnored("other.mp3", isDirectory = false))
    }

    // --- Basename patterns -----------------------------------------------------------------------

    @Test
    fun `a basename pattern matches at any depth`() {
        val m = matcher("*.tmp.mp3")

        assertTrue(m.isIgnored("a.tmp.mp3", false))
        assertTrue(m.isIgnored("album/a.tmp.mp3", false))
        assertTrue(m.isIgnored("album/disc1/a.tmp.mp3", false))
        assertFalse(m.isIgnored("a.mp3", false))
    }

    @Test
    fun `a question mark matches one character`() {
        val m = matcher("draft?.flac")

        assertTrue(m.isIgnored("draft1.flac", false))
        assertFalse(m.isIgnored("draft10.flac", false))
    }

    @Test
    fun `a question mark does not cross a slash`() {
        val m = matcher("a?b")

        assertFalse(m.isIgnored("a/b", false))
    }

    // --- Anchored patterns -----------------------------------------------------------------------

    @Test
    fun `a leading slash anchors to the rule's directory`() {
        val m = matcher("/private.mp3")

        assertTrue(m.isIgnored("private.mp3", false))
        assertFalse(m.isIgnored("sub/private.mp3", false))
    }

    @Test
    fun `a pattern containing a slash is anchored`() {
        val m = matcher("archive/draft.flac")

        assertTrue(m.isIgnored("archive/draft.flac", false))
        assertFalse(m.isIgnored("other/archive/draft.flac", false))
    }

    // --- Double star -----------------------------------------------------------------------------

    @Test
    fun `a leading double-star-slash matches any depth`() {
        val m = matcher("**/draft.flac")

        assertTrue(m.isIgnored("draft.flac", false))
        assertTrue(m.isIgnored("a/draft.flac", false))
        assertTrue(m.isIgnored("a/b/draft.flac", false))
    }

    @Test
    fun `a trailing double star matches everything beneath`() {
        val m = matcher("archive/**")

        assertTrue(m.isIgnored("archive/a.flac", false))
        assertTrue(m.isIgnored("archive/deep/a.flac", false))
    }

    @Test
    fun `a double star in the middle spans directories`() {
        val m = matcher("archive/**/draft?.flac")

        assertTrue(m.isIgnored("archive/draft1.flac", false))
        assertTrue(m.isIgnored("archive/x/y/draft2.flac", false))
        assertFalse(m.isIgnored("archive/draft10.flac", false))
    }

    // --- Directory rules -------------------------------------------------------------------------

    @Test
    fun `a trailing slash matches directories only`() {
        val m = matcher("cache/")

        assertTrue(m.isIgnored("cache", isDirectory = true))
        assertFalse(m.isIgnored("cache", isDirectory = false))
    }

    @Test
    fun `a directory rule covers its contents through the caller`() {
        // The matcher only answers for the directory itself; the walker stops descending, which is
        // what makes the contents ignored. This test pins that the directory is reported.
        val m = matcher("cache/")

        assertTrue(m.isIgnored("cache", true))
        // A file inside is not matched by the rule directly — the walker never asks, because it
        // stopped at the directory.
        assertFalse(m.isIgnored("cache/a.mp3", false))
    }

    // --- Negation --------------------------------------------------------------------------------

    @Test
    fun `a later negation re-includes a file`() {
        val m = matcher(
            """
            *.mp3
            !keep.mp3
            """.trimIndent()
        )

        assertTrue(m.isIgnored("drop.mp3", false))
        assertFalse(m.isIgnored("keep.mp3", false))
    }

    @Test
    fun `the last matching rule wins`() {
        val m = matcher(
            """
            *.mp3
            !keep.mp3
            keep.mp3
            """.trimIndent()
        )

        // Re-ignored by the third rule.
        assertTrue(m.isIgnored("keep.mp3", false))
    }

    @Test
    fun `negation only re-includes what it names`() {
        val m = matcher(
            """
            cache/
            !cache/keep.mp3
            """.trimIndent()
        )

        assertTrue(m.isIgnored("cache", true))
        // The negation names a file, so it does not un-ignore the directory itself.
        assertFalse(m.isIgnored("cache/keep.mp3", false))
    }

    // --- Decisions -------------------------------------------------------------------------------

    @Test
    fun `an unmatched path yields no decision`() {
        val m = matcher("*.flac")

        assertNull(m.decisionFor("song.mp3", false))
    }

    @Test
    fun `a matched path yields true`() {
        val m = matcher("*.flac")

        assertEquals(true, m.decisionFor("song.flac", false))
    }

    @Test
    fun `a negated match yields false`() {
        val m = matcher(
            """
            *.flac
            !keep.flac
            """.trimIndent()
        )

        assertEquals(false, m.decisionFor("keep.flac", false))
    }

    // --- Nested base directories -----------------------------------------------------------------

    @Test
    fun `a nested matcher rebases its paths`() {
        val m = matcher("/private.mp3", base = "album")

        assertTrue(m.isIgnored("album/private.mp3", false))
        assertFalse(m.isIgnored("private.mp3", false))
    }

    @Test
    fun `a nested matcher has no opinion on paths outside its directory`() {
        val m = matcher("*.mp3", base = "album")

        assertNull(m.decisionFor("other/song.mp3", false))
    }

    @Test
    fun `a nested matcher ignores its own directory`() {
        val m = matcher("*.mp3", base = "album")

        // The base directory itself has no local path to match against.
        assertNull(m.decisionFor("album", true))
    }

    // --- Combining matchers ----------------------------------------------------------------------

    @Test
    fun `an inner matcher can re-include what the outer excluded`() {
        val outer = matcher("*.mp3")
        val inner = matcher("!keep.mp3", base = "album")

        assertTrue(isIgnoredByFoliaMatchers(listOf(outer), "album/keep.mp3", false))
        assertFalse(isIgnoredByFoliaMatchers(listOf(outer, inner), "album/keep.mp3", false))
    }

    @Test
    fun `an inner matcher does not affect paths outside it`() {
        val outer = matcher("*.mp3")
        val inner = matcher("!keep.mp3", base = "album")

        assertTrue(isIgnoredByFoliaMatchers(listOf(outer, inner), "other/keep.mp3", false))
    }

    @Test
    fun `no matchers means nothing is ignored`() {
        assertFalse(isIgnoredByFoliaMatchers(emptyList(), "a.mp3", false))
    }

    // --- Realistic file --------------------------------------------------------------------------

    @Test
    fun `the documented example behaves as described`() {
        val m = matcher(
            """
            # 缓存和临时目录
            cache/
            temp/

            # 任意层级的临时音频
            *.tmp.mp3

            # 只排除根目录中的文件
            /private.mp3

            # 排除深层草稿，但保留指定文件
            archive/**/draft?.flac
            !keep.tmp.mp3
            """.trimIndent()
        )

        assertTrue(m.isIgnored("cache", true))
        assertTrue(m.isIgnored("temp", true))
        assertTrue(m.isIgnored("a.tmp.mp3", false))
        assertTrue(m.isIgnored("x/a.tmp.mp3", false))
        assertTrue(m.isIgnored("private.mp3", false))
        assertFalse(m.isIgnored("sub/private.mp3", false))
        assertTrue(m.isIgnored("archive/draft1.flac", false))
        assertTrue(m.isIgnored("archive/x/draft2.flac", false))
        // The negation is last, so it wins over `*.tmp.mp3`.
        assertFalse(m.isIgnored("keep.tmp.mp3", false))
    }

    // --- Path normalisation ----------------------------------------------------------------------

    @Test
    fun `windows separators are normalised`() {
        val m = matcher("archive/draft.flac")

        assertTrue(m.isIgnored("archive\\draft.flac", false))
    }

    @Test
    fun `a leading dot-slash is stripped`() {
        val m = matcher("./song.mp3")

        assertTrue(m.isIgnored("song.mp3", false))
    }

    @Test
    fun `an empty matcher ignores nothing`() {
        assertFalse(FoliaIgnoreMatcher.EMPTY.isIgnored("a.mp3", false))
        assertEquals(0, FoliaIgnoreMatcher.EMPTY.ruleCount)
    }
}
