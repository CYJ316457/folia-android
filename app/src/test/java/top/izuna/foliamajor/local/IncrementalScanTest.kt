package top.izuna.foliamajor.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the incremental rescan diff.
 *
 * The diff exists to avoid tag reads, so almost every test asserts on `toRead` — the expensive
 * bucket. A change that accidentally promotes a relink or an unchanged file into `toRead` is
 * invisible in the UI (the library still looks right) and only shows up as a slow rescan, which is
 * exactly the kind of regression that needs a test rather than a code review.
 */
class IncrementalScanTest {

    private fun file(path: String, size: Long = 1000, mtime: Long = 100) =
        ScannedFile(
            relativePath = path,
            name = path.substringAfterLast('/'),
            sizeBytes = size,
            lastModified = mtime,
        )

    private fun song(
        path: String,
        size: Long = 1000,
        mtime: Long = 100,
        lyric: String? = null,
        lyricFormat: String? = null,
        translation: String? = null,
        cover: String? = null,
        metadataVersion: Int = EMBEDDED_METADATA_VERSION,
    ) = SongSnapshot(
        relativePath = path,
        sizeBytes = size,
        lastModified = mtime,
        sidecarLyricPath = lyric,
        sidecarLyricFormat = lyricFormat,
        translationLyricPath = translation,
        folderCoverPath = cover,
        embeddedMetadataVersion = metadataVersion,
    )

    // --- New and removed -------------------------------------------------------------------------

    @Test
    fun `a new file is read`() {
        val diff = diffScan(emptyList(), buildScanPlan(listOf(file("a.mp3"))))

        assertEquals(1, diff.toRead.size)
        assertTrue(diff.toRelink.isEmpty())
        assertTrue(diff.unchanged.isEmpty())
    }

    @Test
    fun `a file missing from disk is reported as removed`() {
        val diff = diffScan(
            existingSongs = listOf(song("gone.mp3")),
            plan = buildScanPlan(emptyList()),
        )

        assertEquals(listOf("gone.mp3"), diff.removedPaths)
        assertTrue(diff.toRead.isEmpty())
    }

    @Test
    fun `removed paths are sorted`() {
        val diff = diffScan(
            existingSongs = listOf(song("b.mp3"), song("a.mp3")),
            plan = buildScanPlan(emptyList()),
        )

        assertEquals(listOf("a.mp3", "b.mp3"), diff.removedPaths)
    }

    // --- Unchanged -------------------------------------------------------------------------------

    @Test
    fun `an identical file is not re-read`() {
        val diff = diffScan(
            existingSongs = listOf(song("a.mp3")),
            plan = buildScanPlan(listOf(file("a.mp3"))),
        )

        assertTrue("expected no tag reads", diff.toRead.isEmpty())
        assertEquals(1, diff.unchanged.size)
    }

    @Test
    fun `unchanged files report the saved tag reads`() {
        val files = (1..50).map { file("track$it.mp3") }
        val diff = diffScan(
            existingSongs = files.map { song(it.relativePath) },
            plan = buildScanPlan(files),
        )

        assertEquals(0, diff.toRead.size)
        assertEquals(50, diff.tagReadsSaved)
    }

    // --- Audio changes ---------------------------------------------------------------------------

    @Test
    fun `a size change forces a tag read`() {
        val diff = diffScan(
            existingSongs = listOf(song("a.mp3", size = 1000)),
            plan = buildScanPlan(listOf(file("a.mp3", size = 2000))),
        )

        assertEquals(1, diff.toRead.size)
    }

    @Test
    fun `an mtime change forces a tag read even with the same size`() {
        // Re-tagging in place keeps the byte count identical; only the mtime moves.
        val diff = diffScan(
            existingSongs = listOf(song("a.mp3", size = 1000, mtime = 100)),
            plan = buildScanPlan(listOf(file("a.mp3", size = 1000, mtime = 200))),
        )

        assertEquals(1, diff.toRead.size)
    }

    // --- Sidecar relinks -------------------------------------------------------------------------

    @Test
    fun `a newly added lyric file relinks instead of re-reading`() {
        // Dropping a .lrc next to an imported album must not cost a tag read per track.
        val diff = diffScan(
            existingSongs = listOf(song("a.mp3")),
            plan = buildScanPlan(listOf(file("a.mp3"), file("a.lrc"))),
        )

        assertTrue("must not re-read tags", diff.toRead.isEmpty())
        assertEquals(1, diff.toRelink.size)
    }

    @Test
    fun `a removed lyric file relinks`() {
        val diff = diffScan(
            existingSongs = listOf(song("a.mp3", lyric = "a.lrc", lyricFormat = "lrc")),
            plan = buildScanPlan(listOf(file("a.mp3"))),
        )

        assertTrue(diff.toRead.isEmpty())
        assertEquals(1, diff.toRelink.size)
    }

    @Test
    fun `reordering lyric formats relinks the affected tracks`() {
        // "a.krc" and "a.lrc" both exist; the stored record picked lrc. Switching to krc-first
        // changes the winner, which is a link change and nothing more.
        val files = listOf(file("a.mp3"), file("a.lrc"), file("a.krc"))
        val existing = listOf(song("a.mp3", lyric = "a.lrc", lyricFormat = "lrc"))

        val diff = diffScan(
            existingSongs = existing,
            plan = buildScanPlan(files, lyricFormatOrder = listOf("krc", "lrc", "vtt", "ttml", "qrc", "yrc")),
        )

        assertTrue(diff.toRead.isEmpty())
        assertEquals(1, diff.toRelink.size)
    }

    @Test
    fun `an unchanged lyric link is not relinked`() {
        val diff = diffScan(
            existingSongs = listOf(song("a.mp3", lyric = "a.lrc", lyricFormat = "lrc")),
            plan = buildScanPlan(listOf(file("a.mp3"), file("a.lrc"))),
        )

        assertTrue(diff.toRelink.isEmpty())
        assertEquals(1, diff.unchanged.size)
    }

    @Test
    fun `a new translation lyric relinks`() {
        val diff = diffScan(
            existingSongs = listOf(song("a.mp3", lyric = "a.lrc", lyricFormat = "lrc")),
            plan = buildScanPlan(listOf(file("a.mp3"), file("a.lrc"), file("a.t.lrc"))),
        )

        assertTrue(diff.toRead.isEmpty())
        assertEquals(1, diff.toRelink.size)
    }

    @Test
    fun `a new folder cover relinks every track in that folder`() {
        val files = listOf(file("album/a.mp3"), file("album/b.mp3"), file("album/cover.png"))
        val existing = listOf(song("album/a.mp3"), song("album/b.mp3"))

        val diff = diffScan(existingSongs = existing, plan = buildScanPlan(files))

        assertTrue(diff.toRead.isEmpty())
        assertEquals(2, diff.toRelink.size)
    }

    @Test
    fun `a cover in another folder does not relink`() {
        val files = listOf(file("a/track.mp3"), file("b/cover.png"))
        val existing = listOf(song("a/track.mp3"))

        val diff = diffScan(existingSongs = existing, plan = buildScanPlan(files))

        assertEquals(1, diff.unchanged.size)
        assertTrue(diff.toRelink.isEmpty())
    }

    // --- Metadata version ------------------------------------------------------------------------

    @Test
    fun `a record from an older metadata version is re-read`() {
        // This is how a library picks up a field added in a later app version. Without it, every
        // existing song stays missing the new field until the user clears the library.
        val diff = diffScan(
            existingSongs = listOf(song("a.mp3", metadataVersion = EMBEDDED_METADATA_VERSION - 1)),
            plan = buildScanPlan(listOf(file("a.mp3"))),
        )

        assertEquals(1, diff.toRead.size)
    }

    @Test
    fun `a record from the current metadata version is not re-read`() {
        val diff = diffScan(
            existingSongs = listOf(song("a.mp3", metadataVersion = EMBEDDED_METADATA_VERSION)),
            plan = buildScanPlan(listOf(file("a.mp3"))),
        )

        assertTrue(diff.toRead.isEmpty())
        assertEquals(1, diff.unchanged.size)
    }

    @Test
    fun `an outdated record is re-read even when its links are unchanged`() {
        val diff = diffScan(
            existingSongs = listOf(
                song("a.mp3", lyric = "a.lrc", lyricFormat = "lrc", metadataVersion = 0)
            ),
            plan = buildScanPlan(listOf(file("a.mp3"), file("a.lrc"))),
        )

        assertEquals(1, diff.toRead.size)
        assertTrue(diff.toRelink.isEmpty())
    }

    @Test
    fun `the metadata version wins over an unchanged file`() {
        // Same size, same mtime, same links — only the version differs.
        val diff = diffScan(
            existingSongs = listOf(
                song("a.mp3", size = 1000, mtime = 100, lyric = "a.lrc", lyricFormat = "lrc", metadataVersion = 1)
            ),
            plan = buildScanPlan(listOf(file("a.mp3", size = 1000, mtime = 100), file("a.lrc"))),
        )

        assertEquals(1, diff.toRead.size)
    }

    // --- Mixed -----------------------------------------------------------------------------------

    @Test
    fun `an audio change takes precedence over a link change`() {
        // The tag read rebuilds every field including the links, so it must not also be relinked.
        val diff = diffScan(
            existingSongs = listOf(song("a.mp3", size = 1000)),
            plan = buildScanPlan(listOf(file("a.mp3", size = 2000), file("a.lrc"))),
        )

        assertEquals(1, diff.toRead.size)
        assertTrue(diff.toRelink.isEmpty())
    }

    @Test
    fun `a mixed library splits into the three buckets`() {
        val files = listOf(
            file("new.mp3"),                    // new
            file("changed.mp3", size = 5000),   // audio changed
            file("relinked.mp3"),               // lyric appeared
            file("relinked.lrc"),               // not audio: only drives the relink
            file("same.mp3"),                   // nothing changed
        )
        val existing = listOf(
            song("changed.mp3", size = 1000),
            song("relinked.mp3"),
            song("same.mp3"),
        )

        val diff = diffScan(existingSongs = existing, plan = buildScanPlan(files))

        assertEquals(2, diff.toRead.size)
        assertEquals(1, diff.toRelink.size)
        assertEquals(1, diff.unchanged.size)
        assertEquals(2, diff.tagReadsSaved)
        // Four audio files; the .lrc is not a song and never enters the diff.
        assertEquals(4, diff.total)
    }

    @Test
    fun `the totals add up`() {
        val files = (1..10).map { file("t$it.mp3") }
        val existing = (1..5).map { song("t$it.mp3", size = if (it == 3) 999 else 1000) }

        val diff = diffScan(existingSongs = existing, plan = buildScanPlan(files))

        assertEquals(10, diff.total)
        assertEquals(diff.total, diff.toRead.size + diff.toRelink.size + diff.unchanged.size)
    }
}
