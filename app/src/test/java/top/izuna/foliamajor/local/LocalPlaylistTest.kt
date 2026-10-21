package top.izuna.foliamajor.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for playlist operations.
 *
 * The rules worth pinning: the favourites list always exists and cannot be deleted, adding a song
 * twice is a no-op, and dangling song ids are repaired rather than rendered as blanks.
 */
class LocalPlaylistTest {

    private var clock = 1000L
    private fun tick(): Long = clock++

    private fun playlist(
        id: String,
        name: String = id,
        songIds: List<String> = emptyList(),
        isFavorite: Boolean = false,
    ) = LocalPlaylist(
        id = id,
        name = name,
        songIds = songIds,
        createdAt = 1L,
        updatedAt = 1L,
        isFavorite = isFavorite,
    )

    // --- Favourites ------------------------------------------------------------------------------

    @Test
    fun `an empty list gains a favourites playlist`() {
        val result = LocalPlaylistOps.ensureFavorites(emptyList(), now = tick()) { "fav" }

        assertEquals(1, result.size)
        assertTrue(result[0].isFavorite)
        assertEquals(FAVORITE_PLAYLIST_NAME, result[0].name)
    }

    @Test
    fun `an existing favourites playlist is kept`() {
        val existing = playlist("fav", isFavorite = true)

        val result = LocalPlaylistOps.ensureFavorites(listOf(existing), now = tick()) { "new" }

        assertEquals(1, result.size)
        assertSame(existing, result[0])
    }

    @Test
    fun `favourites are placed first`() {
        val result = LocalPlaylistOps.ensureFavorites(
            listOf(playlist("a"), playlist("b")),
            now = tick(),
        ) { "fav" }

        assertTrue(result.first().isFavorite)
        assertEquals(3, result.size)
    }

    @Test
    fun `the favourites playlist cannot be deleted`() {
        val favorite = playlist("fav", isFavorite = true)
        val other = playlist("other")

        assertFalse(LocalPlaylistOps.canDelete(favorite))
        assertEquals(2, LocalPlaylistOps.delete(listOf(favorite, other), "fav").size)
    }

    @Test
    fun `a normal playlist can be deleted`() {
        val list = listOf(playlist("fav", isFavorite = true), playlist("other"))

        assertTrue(LocalPlaylistOps.canDelete(list[1]))
        val result = LocalPlaylistOps.delete(list, "other")
        assertEquals(1, result.size)
        assertEquals("fav", result[0].id)
    }

    @Test
    fun `deleting a missing playlist changes nothing`() {
        val list = listOf(playlist("a"))

        assertEquals(list, LocalPlaylistOps.delete(list, "nope"))
    }

    // --- Create and rename -----------------------------------------------------------------------

    @Test
    fun `creating trims the name`() {
        val (_, created) = LocalPlaylistOps.create(emptyList(), "  我的歌单  ", now = tick()) { "p1" }

        assertEquals("我的歌单", created.name)
    }

    @Test
    fun `creating deduplicates the initial songs`() {
        val (_, created) = LocalPlaylistOps.create(
            emptyList(),
            "list",
            songIds = listOf("a", "a", "b"),
            now = tick(),
        ) { "p1" }

        assertEquals(listOf("a", "b"), created.songIds)
    }

    @Test
    fun `an empty initial song list is allowed`() {
        val (_, created) = LocalPlaylistOps.create(emptyList(), "empty", now = tick()) { "p1" }

        assertTrue(created.songIds.isEmpty())
    }

    @Test
    fun `renaming updates the name and timestamp`() {
        val list = listOf(playlist("a", name = "old"))

        val result = LocalPlaylistOps.rename(list, "a", "new", now = 999L)

        assertEquals("new", result[0].name)
        assertEquals(999L, result[0].updatedAt)
    }

    @Test
    fun `a blank rename is rejected`() {
        val list = listOf(playlist("a", name = "keep"))

        assertSame(list, LocalPlaylistOps.rename(list, "a", "   "))
    }

    @Test
    fun `renaming an unknown playlist changes nothing`() {
        val list = listOf(playlist("a", name = "keep"))

        assertEquals("keep", LocalPlaylistOps.rename(list, "nope", "new")[0].name)
    }

    // --- Adding songs ----------------------------------------------------------------------------

    @Test
    fun `songs are appended in order`() {
        val list = listOf(playlist("p", songIds = listOf("a")))

        val result = LocalPlaylistOps.addSongs(list, "p", listOf("b", "c"), now = tick())

        assertEquals(listOf("a", "b", "c"), result[0].songIds)
    }

    @Test
    fun `adding a song twice is a no-op`() {
        val list = listOf(playlist("p", songIds = listOf("a")))

        val result = LocalPlaylistOps.addSongs(list, "p", listOf("a"), now = tick())

        assertEquals(listOf("a"), result[0].songIds)
        // The same instance is returned, so the caller can skip a write.
        assertSame(list[0], result[0])
    }

    @Test
    fun `duplicates inside one batch are dropped`() {
        val list = listOf(playlist("p"))

        val result = LocalPlaylistOps.addSongs(list, "p", listOf("a", "a", "b"), now = tick())

        assertEquals(listOf("a", "b"), result[0].songIds)
    }

    @Test
    fun `adding to an unknown playlist changes nothing`() {
        val list = listOf(playlist("p"))

        assertEquals(list, LocalPlaylistOps.addSongs(list, "nope", listOf("a")))
    }

    @Test
    fun `adding nothing returns the same list`() {
        val list = listOf(playlist("p"))

        assertSame(list, LocalPlaylistOps.addSongs(list, "p", emptyList()))
    }

    // --- Removing songs --------------------------------------------------------------------------

    @Test
    fun `removing preserves the order of the rest`() {
        val list = listOf(playlist("p", songIds = listOf("a", "b", "c")))

        val result = LocalPlaylistOps.removeSongs(list, "p", listOf("b"), now = tick())

        assertEquals(listOf("a", "c"), result[0].songIds)
    }

    @Test
    fun `removing a missing song is a no-op`() {
        val list = listOf(playlist("p", songIds = listOf("a")))

        assertSame(list[0], LocalPlaylistOps.removeSongs(list, "p", listOf("z"))[0])
    }

    // --- Reordering ------------------------------------------------------------------------------

    @Test
    fun `moving a song forward reorders correctly`() {
        val list = listOf(playlist("p", songIds = listOf("a", "b", "c")))

        val result = LocalPlaylistOps.moveSong(list, "p", fromIndex = 0, toIndex = 2, now = tick())

        assertEquals(listOf("b", "c", "a"), result[0].songIds)
    }

    @Test
    fun `moving a song backward reorders correctly`() {
        val list = listOf(playlist("p", songIds = listOf("a", "b", "c")))

        val result = LocalPlaylistOps.moveSong(list, "p", fromIndex = 2, toIndex = 0, now = tick())

        assertEquals(listOf("c", "a", "b"), result[0].songIds)
    }

    @Test
    fun `a target past the end clamps to the last slot`() {
        val list = listOf(playlist("p", songIds = listOf("a", "b", "c")))

        val result = LocalPlaylistOps.moveSong(list, "p", fromIndex = 0, toIndex = 99, now = tick())

        assertEquals(listOf("b", "c", "a"), result[0].songIds)
    }

    @Test
    fun `moving from an invalid index is ignored`() {
        val list = listOf(playlist("p", songIds = listOf("a")))

        assertEquals(
            listOf("a"),
            LocalPlaylistOps.moveSong(list, "p", fromIndex = 5, toIndex = 0)[0].songIds,
        )
    }

    @Test
    fun `moving to the same index is a no-op`() {
        val list = listOf(playlist("p", songIds = listOf("a", "b")))

        assertSame(list[0], LocalPlaylistOps.moveSong(list, "p", fromIndex = 1, toIndex = 1)[0])
    }

    // --- Favourites toggle -----------------------------------------------------------------------

    @Test
    fun `favouriting adds to the favourites playlist`() {
        val list = listOf(playlist("fav", isFavorite = true))

        val result = LocalPlaylistOps.setFavorite(list, "s1", shouldFavorite = true, now = tick())

        assertTrue(LocalPlaylistOps.isFavorite(result, "s1"))
    }

    @Test
    fun `unfavouriting removes from the favourites playlist`() {
        val list = listOf(playlist("fav", songIds = listOf("s1"), isFavorite = true))

        val result = LocalPlaylistOps.setFavorite(list, "s1", shouldFavorite = false, now = tick())

        assertFalse(LocalPlaylistOps.isFavorite(result, "s1"))
    }

    @Test
    fun `favouriting twice does not duplicate`() {
        val list = listOf(playlist("fav", songIds = listOf("s1"), isFavorite = true))

        val result = LocalPlaylistOps.setFavorite(list, "s1", shouldFavorite = true, now = tick())

        assertEquals(listOf("s1"), result[0].songIds)
    }

    @Test
    fun `toggling without a favourites list changes nothing`() {
        val list = listOf(playlist("a"))

        assertSame(list, LocalPlaylistOps.setFavorite(list, "s1", shouldFavorite = true))
    }

    // --- Repair ----------------------------------------------------------------------------------

    @Test
    fun `repair drops songs that no longer exist`() {
        val playlist = playlist("p", songIds = listOf("a", "gone", "b"))

        val repaired = LocalPlaylistOps.repair(playlist, liveSongIds = setOf("a", "b"))

        assertEquals(listOf("a", "b"), repaired.songIds)
    }

    @Test
    fun `repair keeps the playlist instance when nothing is missing`() {
        val playlist = playlist("p", songIds = listOf("a", "b"))

        assertSame(playlist, LocalPlaylistOps.repair(playlist, liveSongIds = setOf("a", "b")))
    }

    @Test
    fun `repair preserves order`() {
        val playlist = playlist("p", songIds = listOf("c", "a", "b"))

        val repaired = LocalPlaylistOps.repair(playlist, liveSongIds = setOf("a", "b", "c"))

        assertEquals(listOf("c", "a", "b"), repaired.songIds)
    }

    @Test
    fun `repairing down to nothing leaves an empty playlist`() {
        val playlist = playlist("p", songIds = listOf("a"))

        assertTrue(LocalPlaylistOps.repair(playlist, emptySet()).songIds.isEmpty())
    }

    // --- Lookup ----------------------------------------------------------------------------------

    @Test
    fun `the favourites playlist can be looked up`() {
        val list = LocalPlaylistOps.ensureFavorites(listOf(playlist("a")), now = tick()) { "fav" }

        assertNotNull(LocalPlaylistOps.favoritePlaylist(list))
    }

    @Test
    fun `looking up favourites in an uninitialised list yields null`() {
        assertNull(LocalPlaylistOps.favoritePlaylist(listOf(playlist("a"))))
    }

    @Test
    fun `is favourite is false when no favourites playlist exists`() {
        assertFalse(LocalPlaylistOps.isFavorite(listOf(playlist("a")), "s1"))
    }
}
