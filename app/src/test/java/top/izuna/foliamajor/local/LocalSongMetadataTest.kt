package top.izuna.foliamajor.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for title resolution and filename parsing.
 *
 * The title fallback chain is the most visible thing in a local library: get it wrong and every
 * untagged track shows its raw filename. The chain is embedded tag → parsed filename → stem → raw
 * filename, and each step is pinned here.
 */
class LocalSongMetadataTest {

    // --- Title fallback chain --------------------------------------------------------------------

    @Test
    fun `an embedded title wins`() {
        val metadata = buildImportedMetadata(
            fileName = "01 - Wrong Artist - Wrong Title.mp3",
            embeddedTitle = "Real Title",
            fallbackTitle = "Wrong Title",
        )

        assertEquals("Real Title", metadata.title)
        assertEquals(TitleSource.EMBEDDED, metadata.titleSource)
    }

    @Test
    fun `a blank embedded title falls through to the filename`() {
        val metadata = buildImportedMetadata(
            fileName = "01 - Song.mp3",
            embeddedTitle = "   ",
            fallbackTitle = "Parsed Title",
        )

        assertEquals("Parsed Title", metadata.title)
        assertEquals(TitleSource.FILENAME, metadata.titleSource)
    }

    @Test
    fun `the filename stem is used when there is no parsed title`() {
        val metadata = buildImportedMetadata(fileName = "Some Song.mp3")

        assertEquals("Some Song", metadata.title)
        assertEquals(TitleSource.FILENAME, metadata.titleSource)
    }

    @Test
    fun `the raw filename is used when the stem is empty`() {
        val metadata = buildImportedMetadata(fileName = ".mp3")

        assertEquals(".mp3", metadata.title)
    }

    @Test
    fun `embedded title is whitespace-normalised`() {
        val metadata = buildImportedMetadata(
            fileName = "a.mp3",
            embeddedTitle = "  Too   Many    Spaces  ",
        )

        assertEquals("Too Many Spaces", metadata.title)
    }

    // --- Artist resolution -----------------------------------------------------------------------

    @Test
    fun `embedded artist is split`() {
        val metadata = buildImportedMetadata(
            fileName = "a.mp3",
            embeddedArtist = "A; B",
        )

        assertEquals(listOf("A", "B"), metadata.artistNames)
    }

    @Test
    fun `multiple embedded artist tags are flattened`() {
        val metadata = buildImportedMetadata(
            fileName = "a.mp3",
            embeddedArtists = listOf("A; B", "C"),
        )

        assertEquals(listOf("A", "B", "C"), metadata.artistNames)
    }

    @Test
    fun `filename artist is used when there is no tag`() {
        val metadata = buildImportedMetadata(
            fileName = "A - Title.mp3",
            fallbackArtist = "A",
        )

        assertEquals(listOf("A"), metadata.artistNames)
    }

    @Test
    fun `no artist information yields an empty list`() {
        val metadata = buildImportedMetadata(fileName = "a.mp3")

        assertEquals(emptyList<String>(), metadata.artistNames)
    }

    // --- Album resolution ------------------------------------------------------------------------

    @Test
    fun `embedded album wins over the fallback`() {
        val metadata = buildImportedMetadata(
            fileName = "a.mp3",
            embeddedAlbum = "Real Album",
            fallbackAlbum = "Folder Album",
        )

        assertEquals("Real Album", metadata.albumName)
    }

    @Test
    fun `fallback album is used when the tag is absent`() {
        val metadata = buildImportedMetadata(
            fileName = "a.mp3",
            fallbackAlbum = "Folder Album",
        )

        assertEquals("Folder Album", metadata.albumName)
    }

    @Test
    fun `a blank album becomes null rather than an empty string`() {
        val metadata = buildImportedMetadata(fileName = "a.mp3", embeddedAlbum = "   ")

        assertNull(metadata.albumName)
    }

    // --- Filename parsing ------------------------------------------------------------------------

    @Test
    fun `parses artist and title`() {
        val parts = parseFilenameParts("周杰伦 - 晴天.mp3")

        assertEquals("晴天", parts.title)
        assertEquals("周杰伦", parts.artist)
    }

    @Test
    fun `strips a leading track number`() {
        val parts = parseFilenameParts("01 - 周杰伦 - 晴天.flac")

        // The first segment is consumed as a track number, leaving the artist/title pair.
        assertEquals("晴天", parts.title)
        assertEquals("周杰伦", parts.artist)
    }

    @Test
    fun `strips a dot-separated track number`() {
        val parts = parseFilenameParts("03. Artist - Title.mp3")

        assertEquals("Title", parts.title)
        assertEquals("Artist", parts.artist)
    }

    @Test
    fun `does not split on a hyphen without surrounding spaces`() {
        // "Jay-Z" is one word; a bare hyphen is not a separator.
        val parts = parseFilenameParts("Jay-Z - 99 Problems.mp3")

        assertEquals("99 Problems", parts.title)
        assertEquals("Jay-Z", parts.artist)
    }

    @Test
    fun `a hyphen inside a single segment is left alone`() {
        val parts = parseFilenameParts("Jay-Z.mp3")

        assertEquals("Jay-Z", parts.title)
        assertNull(parts.artist)
    }

    @Test
    fun `a filename with no separator has no artist`() {
        val parts = parseFilenameParts("Just A Title.mp3")

        assertEquals("Just A Title", parts.title)
        assertNull(parts.artist)
    }

    @Test
    fun `parses an en dash separator`() {
        val parts = parseFilenameParts("Artist – Title.mp3")

        assertEquals("Title", parts.title)
        assertEquals("Artist", parts.artist)
    }

    @Test
    fun `parses an em dash separator`() {
        val parts = parseFilenameParts("Artist — Title.mp3")

        assertEquals("Title", parts.title)
        assertEquals("Artist", parts.artist)
    }

    @Test
    fun `an empty side falls back to the whole stem`() {
        val parts = parseFilenameParts("Artist - .mp3")

        assertEquals("Artist -", parts.title)
        assertNull(parts.artist)
    }

    @Test
    fun `leading integer parsing takes the numerator`() {
        // MediaMetadataRetriever reports "3/12" on some containers.
        assertEquals(3, parseLeadingInt("3/12"))
        assertEquals(3, parseLeadingInt("3"))
        assertNull(parseLeadingInt("A1"))
        assertNull(parseLeadingInt(""))
    }
}
