package top.izuna.foliamajor.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the import plan and metadata resolution.
 *
 * The scan plan decides which lyric file wins for a track and which cover applies to a folder.
 * Both are "last writer wins" hazards in a naive implementation — the file that happens to sort
 * first would win — so the priority rules are pinned here.
 */
class ScanPlanTest {

    private fun file(path: String, size: Long = 1000, mtime: Long = 100) =
        ScannedFile(
            relativePath = path,
            name = path.substringAfterLast('/'),
            sizeBytes = size,
            lastModified = mtime,
        )

    // --- Audio selection -------------------------------------------------------------------------

    @Test
    fun `only audio files become songs`() {
        val plan = buildScanPlan(
            listOf(
                file("album/track.mp3"),
                file("album/track.lrc"),
                file("album/cover.jpg"),
                file("album/notes.txt"),
            )
        )

        assertEquals(listOf("album/track.mp3"), plan.audioFiles.map { it.relativePath })
    }

    @Test
    fun `audio files are sorted by path`() {
        val plan = buildScanPlan(listOf(file("b.mp3"), file("a.mp3"), file("c.mp3")))

        assertEquals(listOf("a.mp3", "b.mp3", "c.mp3"), plan.audioFiles.map { it.relativePath })
    }

    @Test
    fun `unplayable formats are reported separately`() {
        val plan = buildScanPlan(listOf(file("a.mp3"), file("b.ape"), file("c.wv")))

        assertEquals(3, plan.audioFiles.size)
        assertEquals(listOf("b.ape", "c.wv"), plan.skippedUnplayable.map { it.relativePath })
    }

    // --- Lyric matching --------------------------------------------------------------------------

    @Test
    fun `matches a plain sidecar lyric`() {
        val audio = file("album/track.mp3")
        val plan = buildScanPlan(listOf(audio, file("album/track.lrc")))

        assertEquals("album/track.lrc", plan.lyricFor(audio)?.relativePath)
        assertEquals("lrc", plan.lyricFor(audio)?.format)
    }

    @Test
    fun `matches a sidecar lyric that repeats the audio extension`() {
        // "track.mp3.lrc" is a common naming from tag editors and must match "track.mp3".
        val audio = file("album/track.mp3")
        val plan = buildScanPlan(listOf(audio, file("album/track.mp3.lrc")))

        assertEquals("album/track.mp3.lrc", plan.lyricFor(audio)?.relativePath)
    }

    @Test
    fun `picks the lyric format the order ranks first, not the one that sorts first`() {
        // "a.krc" sorts before "a.lrc" alphabetically, but LRC is the default first format.
        val audio = file("track.mp3")
        val plan = buildScanPlan(listOf(audio, file("track.krc"), file("track.lrc")))

        assertEquals("track.lrc", plan.lyricFor(audio)?.relativePath)
    }

    @Test
    fun `honours a custom lyric format order`() {
        val audio = file("track.mp3")
        val krcFirst = listOf("krc", "lrc", "vtt", "ttml", "qrc", "yrc")
        val plan = buildScanPlan(
            listOf(audio, file("track.krc"), file("track.lrc")),
            lyricFormatOrder = krcFirst,
        )

        assertEquals("track.krc", plan.lyricFor(audio)?.relativePath)
    }

    @Test
    fun `two tracks in one folder keep their own lyrics`() {
        val first = file("album/one.mp3")
        val second = file("album/two.mp3")
        val plan = buildScanPlan(
            listOf(first, second, file("album/one.lrc"), file("album/two.krc"))
        )

        assertEquals("album/one.lrc", plan.lyricFor(first)?.relativePath)
        assertEquals("album/two.krc", plan.lyricFor(second)?.relativePath)
    }

    @Test
    fun `a track with no lyric gets null`() {
        val audio = file("album/track.mp3")
        val plan = buildScanPlan(listOf(audio, file("album/other.lrc")))

        assertNull(plan.lyricFor(audio))
    }

    @Test
    fun `matches a translation lyric`() {
        val audio = file("album/track.mp3")
        val plan = buildScanPlan(listOf(audio, file("album/track.t.lrc")))

        assertEquals("album/track.t.lrc", plan.translationLyricFor(audio))
        // The translation must not also be picked as the main lyric.
        assertNull(plan.lyricFor(audio))
    }

    @Test
    fun `translation with the audio extension repeated`() {
        val audio = file("album/track.flac")
        val plan = buildScanPlan(listOf(audio, file("album/track.flac.t.vtt")))

        assertEquals("album/track.flac.t.vtt", plan.translationLyricFor(audio))
    }

    @Test
    fun `lrc and t-lrc for the same track do not collide`() {
        val audio = file("track.mp3")
        val plan = buildScanPlan(
            listOf(audio, file("track.lrc"), file("track.t.lrc"))
        )

        assertEquals("track.lrc", plan.lyricFor(audio)?.relativePath)
        assertEquals("track.t.lrc", plan.translationLyricFor(audio))
    }

    // --- Folder covers ---------------------------------------------------------------------------

    @Test
    fun `a folder cover applies to the files in its own folder`() {
        val audio = file("album/disc1/track.mp3")
        val plan = buildScanPlan(listOf(audio, file("album/disc1/cover.png")))

        assertEquals("album/disc1/cover.png", plan.coverFor(audio))
    }

    @Test
    fun `a cover does not leak into subdirectories`() {
        val nested = file("album/disc1/track.mp3")
        val plan = buildScanPlan(listOf(nested, file("album/cover.png")))

        assertNull(plan.coverFor(nested))
    }

    @Test
    fun `png beats jpg in the same folder`() {
        val audio = file("album/track.mp3")
        val plan = buildScanPlan(
            listOf(audio, file("album/cover.jpg"), file("album/cover.png"))
        )

        assertEquals("album/cover.png", plan.coverFor(audio))
    }

    @Test
    fun `separate folders get separate covers`() {
        val first = file("a/track.mp3")
        val second = file("b/track.mp3")
        val plan = buildScanPlan(
            listOf(first, second, file("a/cover.png"), file("b/cover.jpg"))
        )

        assertEquals("a/cover.png", plan.coverFor(first))
        assertEquals("b/cover.jpg", plan.coverFor(second))
    }

    // --- Change detection ------------------------------------------------------------------------

    @Test
    fun `a new file counts as changed`() {
        assertTrue(hasFileChanged(null, file("a.mp3")))
    }

    @Test
    fun `an unchanged file does not count as changed`() {
        assertTrue(!hasFileChanged(file("a.mp3", 100, 5), file("a.mp3", 100, 5)))
    }

    @Test
    fun `a size change counts as changed`() {
        assertTrue(hasFileChanged(file("a.mp3", 100, 5), file("a.mp3", 200, 5)))
    }

    @Test
    fun `an mtime change counts as changed even when the size is identical`() {
        // Re-tagging in place keeps the size; only the mtime moves.
        assertTrue(hasFileChanged(file("a.mp3", 100, 5), file("a.mp3", 100, 6)))
    }

    @Test
    fun `song ids are the relative path`() {
        assertEquals("album/track.mp3", localSongId("album/track.mp3"))
    }
}
