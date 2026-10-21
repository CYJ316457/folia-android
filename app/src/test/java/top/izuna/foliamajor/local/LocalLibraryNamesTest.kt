package top.izuna.foliamajor.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the local library's matching rules.
 *
 * These are the rules that decide what the library looks like: which file becomes a song, which
 * lyric belongs to it, which cover wins, and what a track is called. Every one of them is invisible
 * in the UI until it is wrong — a mis-split artist shows up as a phantom entry, a mis-picked lyric
 * as untimed text.
 */
class LocalLibraryNamesTest {

    // --- Name normalisation ----------------------------------------------------------------------

    @Test
    fun `normalisation folds full-width forms`() {
        // NFKC turns full-width latin and full-width punctuation into their ASCII forms.
        assertEquals(normalizeLocalLibraryName("ＡＢＣ"), normalizeLocalLibraryName("ABC"))
        assertEquals(normalizeLocalLibraryName("周杰倫"), normalizeLocalLibraryName("周杰倫"))
    }

    @Test
    fun `normalisation is case insensitive`() {
        assertEquals("the beatles", normalizeLocalLibraryName("The Beatles"))
    }

    @Test
    fun `normalisation collapses whitespace`() {
        assertEquals("a b", normalizeLocalLibraryName("  a   b  "))
    }

    @Test
    fun `cleaning preserves case but collapses whitespace`() {
        assertEquals("The Beatles", cleanLocalLibraryName("The  Beatles"))
        assertEquals("The Beatles", cleanLocalLibraryName("  The Beatles  "))
    }

    @Test
    fun `cleaning returns null for blank input`() {
        assertNull(cleanLocalLibraryName("   "))
        assertNull(cleanLocalLibraryName(null))
    }

    // --- Artist splitting ------------------------------------------------------------------------

    @Test
    fun `splits on semicolon`() {
        assertEquals(listOf("A", "B"), splitLocalLibraryArtistNames("A; B"))
    }

    @Test
    fun `splits on full-width semicolon`() {
        assertEquals(listOf("周杰伦", "方文山"), splitLocalLibraryArtistNames("周杰伦；方文山"))
    }

    @Test
    fun `splits on slash`() {
        assertEquals(listOf("A", "B"), splitLocalLibraryArtistNames("A/B"))
    }

    @Test
    fun `splits on full-width slash`() {
        assertEquals(listOf("A", "B"), splitLocalLibraryArtistNames("A／B"))
    }

    @Test
    fun `does not split on comma`() {
        // "Earth, Wind & Fire" is one artist. Splitting on comma is the classic phantom-artist bug.
        assertEquals(listOf("Earth, Wind & Fire"), splitLocalLibraryArtistNames("Earth, Wind & Fire"))
    }

    @Test
    fun `does not split on ampersand`() {
        assertEquals(listOf("Simon & Garfunkel"), splitLocalLibraryArtistNames("Simon & Garfunkel"))
    }

    @Test
    fun `deduplicates names by normalised form`() {
        // Same name differing only in case collapses; a genuinely different credit ("the Beatles")
        // is kept, because normalisation lowercases but does not strip articles.
        assertEquals(listOf("Beatles"), splitLocalLibraryArtistNames("Beatles; BEATLES"))
        assertEquals(
            listOf("Beatles", "the Beatles"),
            splitLocalLibraryArtistNames("Beatles; the Beatles"),
        )
    }

    @Test
    fun `drops empty segments`() {
        assertEquals(listOf("A", "B"), splitLocalLibraryArtistNames("A; ; B;"))
    }

    @Test
    fun `returns empty list for blank input`() {
        assertTrue(splitLocalLibraryArtistNames(null).isEmpty())
        assertTrue(splitLocalLibraryArtistNames("   ").isEmpty())
    }

    // --- File classification ---------------------------------------------------------------------

    @Test
    fun `recognises audio files case-insensitively`() {
        assertTrue(isKnownAudioFile("song.mp3"))
        assertTrue(isKnownAudioFile("song.FLAC"))
        assertTrue(isKnownAudioFile("song.M4a"))
        assertFalse(isKnownAudioFile("song.lrc"))
        assertFalse(isKnownAudioFile("cover.jpg"))
    }

    @Test
    fun `recognises lyric and translation files`() {
        assertTrue(isLyricFile("track.lrc"))
        assertTrue(isLyricFile("track.TTML"))
        assertFalse(isLyricFile("track.t.lrc"))
        assertTrue(isTranslationLyricFile("track.t.lrc"))
        assertTrue(isTranslationLyricFile("track.t.VTT"))
        assertFalse(isTranslationLyricFile("track.lrc"))
    }

    @Test
    fun `separates android-playable from desktop-only formats`() {
        // The desktop app transcodes ape/wv/tta through ffmpeg; Android has no equivalent yet.
        assertTrue(isPlayableOnAndroid("song.mp3"))
        assertTrue(isPlayableOnAndroid("song.flac"))
        assertFalse(isPlayableOnAndroid("song.ape"))
        assertTrue(isKnownAudioFile("song.ape"))
    }

    @Test
    fun `strips the audio extension`() {
        assertEquals("01 - Song", stripLocalAudioExtension("01 - Song.mp3"))
        assertEquals("Song", stripLocalAudioExtension("Song.FLAC"))
        assertEquals("no-extension", stripLocalAudioExtension("no-extension"))
    }

    // --- Sidecar base paths ----------------------------------------------------------------------

    @Test
    fun `sidecar base path handles track dot lrc`() {
        assertEquals("album/track", sidecarLyricBasePath("album/track.lrc", isTranslation = false))
    }

    @Test
    fun `sidecar base path handles track dot mp3 dot lrc`() {
        // Without stripping the audio extension too, this would key as "track.mp3" and never match.
        assertEquals("album/track", sidecarLyricBasePath("album/track.mp3.lrc", isTranslation = false))
    }

    @Test
    fun `translation sidecar base path handles both forms`() {
        assertEquals("track", sidecarLyricBasePath("track.t.lrc", isTranslation = true))
        assertEquals("track", sidecarLyricBasePath("track.flac.t.vtt", isTranslation = true))
    }

    // --- Format priority -------------------------------------------------------------------------

    @Test
    fun `default order ranks lrc first`() {
        assertTrue(localLyricFilePriority("a.lrc") < localLyricFilePriority("a.krc"))
        assertTrue(localLyricFilePriority("a.vtt") < localLyricFilePriority("a.ttml"))
    }

    @Test
    fun `fia always wins`() {
        assertTrue(localLyricFilePriority("a.fia") < localLyricFilePriority("a.lrc"))
    }

    @Test
    fun `a custom order is honoured`() {
        val krcFirst = listOf("krc", "lrc", "vtt", "ttml", "qrc", "yrc")
        assertTrue(localLyricFilePriority("a.krc", krcFirst) < localLyricFilePriority("a.lrc", krcFirst))
    }

    @Test
    fun `unknown formats rank last`() {
        assertEquals(Int.MAX_VALUE, localLyricFilePriority("a.txt"))
    }

    @Test
    fun `format order normalisation appends missing formats`() {
        val normalized = normalizeLocalLyricFormatOrder(listOf("krc"))

        assertEquals("krc", normalized.first())
        assertEquals(LOCAL_LYRIC_FILE_FORMATS.toSet(), normalized.toSet())
    }

    @Test
    fun `format order normalisation drops duplicates and unknown entries`() {
        val normalized = normalizeLocalLyricFormatOrder(listOf("lrc", "lrc", "bogus", "vtt"))

        assertEquals(normalized.distinct(), normalized)
        assertFalse(normalized.contains("bogus"))
    }

    // --- Folder covers ---------------------------------------------------------------------------

    @Test
    fun `prefers png cover over jpg`() {
        assertEquals("cover.png", preferredFolderCover(listOf("cover.jpg", "cover.png")))
    }

    @Test
    fun `cover matching is case insensitive`() {
        assertEquals("Cover.PNG", preferredFolderCover(listOf("Cover.PNG")))
    }

    @Test
    fun `returns null when there is no cover`() {
        assertNull(preferredFolderCover(listOf("song.mp3", "folder.jpg")))
    }

    // --- Paths -----------------------------------------------------------------------------------

    @Test
    fun `parent path of a nested file`() {
        assertEquals("album/disc1", parentRelativePath("album/disc1/track.mp3"))
    }

    @Test
    fun `parent path of a root file is empty`() {
        assertEquals("", parentRelativePath("track.mp3"))
    }
}
