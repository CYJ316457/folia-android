package top.izuna.foliamajor.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for assigning imported songs to entities.
 *
 * The behaviours pinned here are the ones that decide whether an album stays one album. A
 * compilation with a different artist on every track is the hard case: without the album context key
 * and the artist-overlap rule, it fragments into one album entity per track.
 */
class LibraryCatalogTest {

    @Before
    fun resetIds() {
        EntityIdGenerator.resetForTest()
    }

    private fun song(
        id: String,
        title: String = id,
        artists: List<String> = emptyList(),
        album: String? = null,
        relativePath: String = "$id.mp3",
        rootUri: String = "content://root/Music",
    ) = LocalLibraryStore.SongRecord(
        id = id,
        relativePath = relativePath,
        rootUri = rootUri,
        fileName = relativePath.substringAfterLast('/'),
        title = title,
        titleSource = TitleSource.EMBEDDED.name,
        artistNames = artists,
        albumName = album,
    )

    // --- Artists ---------------------------------------------------------------------------------

    @Test
    fun `a song's artists become entities`() {
        val result = assignImportedSongs(
            songs = listOf(song("s1", artists = listOf("A", "B"))),
            existingEntities = emptyList(),
            existingAssignments = emptyList(),
        )

        assertEquals(2, result.entities.size)
        assertEquals(2, result.assignments.single().artistEntityIds.size)
    }

    @Test
    fun `two songs by the same artist share one entity`() {
        val result = assignImportedSongs(
            songs = listOf(
                song("s1", artists = listOf("A")),
                song("s2", artists = listOf("A")),
            ),
            existingEntities = emptyList(),
            existingAssignments = emptyList(),
        )

        assertEquals(1, result.entities.count { it.kind == EntityKind.ARTIST })
        assertEquals(
            result.assignments[0].artistEntityIds,
            result.assignments[1].artistEntityIds,
        )
    }

    @Test
    fun `an artist spelled differently in case shares one entity`() {
        val result = assignImportedSongs(
            songs = listOf(
                song("s1", artists = listOf("The Beatles")),
                song("s2", artists = listOf("the beatles")),
            ),
            existingEntities = emptyList(),
            existingAssignments = emptyList(),
        )

        assertEquals(1, result.entities.count { it.kind == EntityKind.ARTIST })
    }

    @Test
    fun `a song with no artist gets no artist assignment`() {
        val result = assignImportedSongs(
            songs = listOf(song("s1")),
            existingEntities = emptyList(),
            existingAssignments = emptyList(),
        )

        assertTrue(result.assignments.single().artistEntityIds.isEmpty())
        assertTrue(result.entities.isEmpty())
    }

    // --- Albums ----------------------------------------------------------------------------------

    @Test
    fun `songs in the same folder with the same album share one album entity`() {
        val result = assignImportedSongs(
            songs = listOf(
                song("s1", artists = listOf("A"), album = "Album", relativePath = "d/s1.mp3"),
                song("s2", artists = listOf("B"), album = "Album", relativePath = "d/s2.mp3"),
            ),
            existingEntities = emptyList(),
            existingAssignments = emptyList(),
        )

        val albumIds = result.assignments.map { it.albumEntityId }
        assertEquals(1, albumIds.distinct().size)
        assertEquals(1, result.entities.count { it.kind == EntityKind.ALBUM })
    }

    @Test
    fun `a compilation does not fragment into one album per artist`() {
        // Every track has a different artist; the shared album name and folder must still win.
        val result = assignImportedSongs(
            songs = listOf(
                song("s1", artists = listOf("Artist One"), album = "Compilation", relativePath = "comp/01.mp3"),
                song("s2", artists = listOf("Artist Two"), album = "Compilation", relativePath = "comp/02.mp3"),
                song("s3", artists = listOf("Artist Three"), album = "Compilation", relativePath = "comp/03.mp3"),
            ),
            existingEntities = emptyList(),
            existingAssignments = emptyList(),
        )

        assertEquals(1, result.entities.count { it.kind == EntityKind.ALBUM })
        assertEquals(3, result.entities.count { it.kind == EntityKind.ARTIST })
    }

    @Test
    fun `the same album name in different folders collapses into one entity`() {
        // Matches the desktop app: the resolver matches albums by name, and the folder only decides
        // the *context* used to break ties. Two folders with the same album title therefore share
        // one entity, and the user splits them by hand if they are genuinely different albums.
        val result = assignImportedSongs(
            songs = listOf(
                song("s1", artists = listOf("A"), album = "Greatest Hits", relativePath = "a/s1.mp3"),
                song("s2", artists = listOf("B"), album = "Greatest Hits", relativePath = "b/s2.mp3"),
            ),
            existingEntities = emptyList(),
            existingAssignments = emptyList(),
        )

        val albumIds = result.assignments.mapNotNull { it.albumEntityId }
        assertEquals(1, albumIds.distinct().size)
    }

    @Test
    fun `albums with different names stay separate`() {
        val result = assignImportedSongs(
            songs = listOf(
                song("s1", artists = listOf("A"), album = "Album One", relativePath = "a/s1.mp3"),
                song("s2", artists = listOf("A"), album = "Album Two", relativePath = "a/s2.mp3"),
            ),
            existingEntities = emptyList(),
            existingAssignments = emptyList(),
        )

        val albumIds = result.assignments.mapNotNull { it.albumEntityId }
        assertEquals(2, albumIds.distinct().size)
    }

    @Test
    fun `the same album name in different folders joins through a shared artist`() {
        // This is the desktop app's rule: a single album already credited to this artist is stronger
        // evidence than the folder path, so the two folders' albums collapse into one entity.
        val result = assignImportedSongs(
            songs = listOf(
                song("s1", artists = listOf("A"), album = "Greatest Hits", relativePath = "a/s1.mp3"),
                song("s2", artists = listOf("A"), album = "Greatest Hits", relativePath = "b/s2.mp3"),
            ),
            existingEntities = emptyList(),
            existingAssignments = emptyList(),
        )

        val albumIds = result.assignments.mapNotNull { it.albumEntityId }
        assertEquals(1, albumIds.distinct().size)
    }

    @Test
    fun `a song with no album gets no album assignment`() {
        val result = assignImportedSongs(
            songs = listOf(song("s1", artists = listOf("A"))),
            existingEntities = emptyList(),
            existingAssignments = emptyList(),
        )

        assertNull(result.assignments.single().albumEntityId)
    }

    // --- Artist overlap --------------------------------------------------------------------------

    @Test
    fun `a song joins an existing album through a shared artist`() {
        // The first import created artist A on album X. A later song by A with the same album name
        // but a different folder should still land on the same album entity.
        val first = assignImportedSongs(
            songs = listOf(song("s1", artists = listOf("A"), album = "X", relativePath = "x/s1.mp3")),
            existingEntities = emptyList(),
            existingAssignments = emptyList(),
        )
        val albumId = first.assignments.single().albumEntityId

        val second = assignImportedSongs(
            songs = listOf(song("s2", artists = listOf("A"), album = "X", relativePath = "other/s2.mp3")),
            existingEntities = first.entities,
            existingAssignments = first.assignments,
        )

        assertEquals(albumId, second.assignments.last().albumEntityId)
    }

    // --- Preserving user decisions ---------------------------------------------------------------

    @Test
    fun `a manual artist assignment survives a rescan`() {
        val first = assignImportedSongs(
            songs = listOf(song("s1", artists = listOf("A"))),
            existingEntities = emptyList(),
            existingAssignments = emptyList(),
        )
        val manual = first.assignments.single().copy(
            artistEntityIds = listOf("manual-artist"),
            artistOrigin = AssignmentOrigin.MANUAL,
        )

        val second = assignImportedSongs(
            songs = listOf(song("s1", artists = listOf("A"))),
            existingEntities = first.entities,
            existingAssignments = listOf(manual),
            preserveNonImportAssignments = true,
        )

        assertEquals(listOf("manual-artist"), second.assignments.single().artistEntityIds)
        assertEquals(AssignmentOrigin.MANUAL, second.assignments.single().artistOrigin)
    }

    @Test
    fun `a manual assignment is recomputed when preservation is off`() {
        val first = assignImportedSongs(
            songs = listOf(song("s1", artists = listOf("A"))),
            existingEntities = emptyList(),
            existingAssignments = emptyList(),
        )
        val manual = first.assignments.single().copy(
            artistEntityIds = listOf("manual-artist"),
            artistOrigin = AssignmentOrigin.MANUAL,
        )

        val second = assignImportedSongs(
            songs = listOf(song("s1", artists = listOf("A"))),
            existingEntities = first.entities,
            existingAssignments = listOf(manual),
            preserveNonImportAssignments = false,
        )

        assertNotEquals(listOf("manual-artist"), second.assignments.single().artistEntityIds)
        assertEquals(AssignmentOrigin.IMPORT, second.assignments.single().artistOrigin)
    }

    @Test
    fun `assignments for songs outside the batch are kept`() {
        val first = assignImportedSongs(
            songs = listOf(song("s1", artists = listOf("A")), song("s2", artists = listOf("B"))),
            existingEntities = emptyList(),
            existingAssignments = emptyList(),
        )

        val second = assignImportedSongs(
            songs = listOf(song("s1", artists = listOf("A"))),
            existingEntities = first.entities,
            existingAssignments = first.assignments,
        )

        assertEquals(2, second.assignments.size)
        assertTrue(second.assignments.any { it.songId == "s2" })
    }

    // --- Reporting -------------------------------------------------------------------------------

    @Test
    fun `created entity ids are reported`() {
        val result = assignImportedSongs(
            songs = listOf(song("s1", artists = listOf("A", "B"), album = "X")),
            existingEntities = emptyList(),
            existingAssignments = emptyList(),
        )

        // Two artists and one album.
        assertEquals(3, result.createdEntityIds.size)
        assertEquals(result.entities.size, result.createdEntityIds.size)
    }

    @Test
    fun `an empty batch changes nothing`() {
        val result = assignImportedSongs(
            songs = emptyList(),
            existingEntities = listOf(
                LibraryEntity("a1", EntityKind.ARTIST, "A", listOf("A"), listOf("a"))
            ),
            existingAssignments = emptyList(),
        )

        assertEquals(1, result.entities.size)
        assertTrue(result.assignments.isEmpty())
        assertTrue(result.createdEntityIds.isEmpty())
    }

    // --- Context key -----------------------------------------------------------------------------

    @Test
    fun `the album context key includes the folder`() {
        val first = song("s1", album = "X", relativePath = "a/s1.mp3")
        val second = song("s2", album = "X", relativePath = "b/s2.mp3")

        assertNotEquals(
            albumImportContextKey(first, "X"),
            albumImportContextKey(second, "X"),
        )
    }

    @Test
    fun `the album context key ignores artist`() {
        val first = song("s1", artists = listOf("A"), album = "X", relativePath = "d/s1.mp3")
        val second = song("s2", artists = listOf("B"), album = "X", relativePath = "d/s2.mp3")

        assertEquals(
            albumImportContextKey(first, "X"),
            albumImportContextKey(second, "X"),
        )
    }
}
