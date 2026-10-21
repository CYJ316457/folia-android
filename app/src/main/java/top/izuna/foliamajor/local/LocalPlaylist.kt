package top.izuna.foliamajor.local

/**
 * Local playlists, ported from folia-major's `src/services/localPlaylistService.ts`.
 *
 * A playlist is a named, ordered list of song ids. Two design decisions are worth carrying over
 * exactly, because both prevent a class of bug:
 *
 *  - **The favourites playlist is a normal playlist with `isFavorite = true`.** It is created on
 *    first use and cannot be deleted. Modelling favourites as a separate table would duplicate
 *    every ordering, renaming and cleanup rule.
 *  - **Song ids are repaired on read, not on write.** A song can vanish (file deleted, folder
 *    removed) without the playlist being touched, so the playlist is filtered against the live
 *    library every time it is loaded. Doing it on write would leave dangling ids behind whenever a
 *    song disappeared through some other path.
 */

data class LocalPlaylist(
    val id: String,
    val name: String,
    /** Ordered; the user's ordering is the playlist's meaning and is never sorted implicitly. */
    val songIds: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
    /** The favourites list. Exactly one playlist may hold this flag. */
    val isFavorite: Boolean = false,
)

/** The name the favourites list is created with. */
const val FAVORITE_PLAYLIST_NAME = "我喜欢"

/**
 * Playlist operations, all pure.
 *
 * The store owns persistence; these functions only compute the next list, which makes every rule
 * testable without a device or a file.
 */
object LocalPlaylistOps {

    private fun generateId(now: Long): String = "playlist-$now-${(0..0xFFFF).random().toString(16)}"

    /**
     * Ensures the favourites playlist exists, and returns the list unchanged when it already does.
     *
     * Called on every read rather than once at install: a user who deletes the app's data should get
     * the favourites list back without having to create it by hand.
     */
    fun ensureFavorites(
        playlists: List<LocalPlaylist>,
        now: Long = System.currentTimeMillis(),
        createId: (Long) -> String = ::generateId,
    ): List<LocalPlaylist> {
        if (playlists.any { it.isFavorite }) return playlists

        val favorite = LocalPlaylist(
            id = createId(now),
            name = FAVORITE_PLAYLIST_NAME,
            songIds = emptyList(),
            createdAt = now,
            updatedAt = now,
            isFavorite = true,
        )
        return listOf(favorite) + playlists
    }

    /**
     * Filters out song ids that no longer exist.
     *
     * Returns the playlist unchanged (same instance) when nothing was removed, so callers can skip a
     * write by identity comparison.
     */
    fun repair(playlist: LocalPlaylist, liveSongIds: Set<String>): LocalPlaylist {
        val kept = playlist.songIds.filter { it in liveSongIds }
        return if (kept.size == playlist.songIds.size) playlist else playlist.copy(songIds = kept)
    }

    fun create(
        playlists: List<LocalPlaylist>,
        name: String,
        songIds: List<String> = emptyList(),
        now: Long = System.currentTimeMillis(),
        createId: (Long) -> String = ::generateId,
    ): Pair<List<LocalPlaylist>, LocalPlaylist> {
        val playlist = LocalPlaylist(
            id = createId(now),
            name = name.trim(),
            songIds = songIds.distinct(),
            createdAt = now,
            updatedAt = now,
        )
        return (playlists + playlist) to playlist
    }

    /** Renames a playlist. A blank name is rejected rather than silently accepted. */
    fun rename(
        playlists: List<LocalPlaylist>,
        playlistId: String,
        name: String,
        now: Long = System.currentTimeMillis(),
    ): List<LocalPlaylist> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return playlists

        return playlists.map { playlist ->
            if (playlist.id == playlistId) playlist.copy(name = trimmed, updatedAt = now) else playlist
        }
    }

    /**
     * Deletes a playlist.
     *
     * The favourites list cannot be deleted: it is the destination of every "favourite" action, and
     * removing it would leave those actions with nowhere to write.
     */
    fun delete(playlists: List<LocalPlaylist>, playlistId: String): List<LocalPlaylist> {
        val target = playlists.firstOrNull { it.id == playlistId } ?: return playlists
        if (target.isFavorite) return playlists

        return playlists.filterNot { it.id == playlistId }
    }

    fun canDelete(playlist: LocalPlaylist?): Boolean = playlist != null && !playlist.isFavorite

    /**
     * Appends songs, skipping ones already present.
     *
     * Duplicates are rejected by id, not by position: adding a song twice would show it twice and
     * play it twice, which no user expects from a playlist.
     */
    fun addSongs(
        playlists: List<LocalPlaylist>,
        playlistId: String,
        songIds: List<String>,
        now: Long = System.currentTimeMillis(),
    ): List<LocalPlaylist> {
        if (songIds.isEmpty()) return playlists

        return playlists.map { playlist ->
            if (playlist.id != playlistId) return@map playlist

            val existing = playlist.songIds.toSet()
            val additions = songIds.filterNot { it in existing }.distinct()
            if (additions.isEmpty()) {
                playlist
            } else {
                playlist.copy(songIds = playlist.songIds + additions, updatedAt = now)
            }
        }
    }

    fun removeSongs(
        playlists: List<LocalPlaylist>,
        playlistId: String,
        songIds: List<String>,
        now: Long = System.currentTimeMillis(),
    ): List<LocalPlaylist> {
        if (songIds.isEmpty()) return playlists
        val removed = songIds.toSet()

        return playlists.map { playlist ->
            if (playlist.id != playlistId) return@map playlist

            val kept = playlist.songIds.filterNot { it in removed }
            if (kept.size == playlist.songIds.size) {
                playlist
            } else {
                playlist.copy(songIds = kept, updatedAt = now)
            }
        }
    }

    /**
     * Moves one song within a playlist.
     *
     * Out-of-range indices are clamped rather than rejected: a drag that ends slightly past the last
     * row should move the song to the end, not silently do nothing.
     */
    fun moveSong(
        playlists: List<LocalPlaylist>,
        playlistId: String,
        fromIndex: Int,
        toIndex: Int,
        now: Long = System.currentTimeMillis(),
    ): List<LocalPlaylist> {
        return playlists.map { playlist ->
            if (playlist.id != playlistId) return@map playlist
            if (fromIndex !in playlist.songIds.indices) return@map playlist

            val ids = playlist.songIds.toMutableList()
            val target = toIndex.coerceIn(0, ids.lastIndex)
            if (target == fromIndex) return@map playlist

            val moved = ids.removeAt(fromIndex)
            ids.add(target, moved)
            playlist.copy(songIds = ids, updatedAt = now)
        }
    }

    /**
     * Sets whether a song is in the favourites list.
     *
     * Returns the list unchanged when the song is already in the requested state, so a repeated tap
     * does not reorder the favourites list.
     */
    fun setFavorite(
        playlists: List<LocalPlaylist>,
        songId: String,
        shouldFavorite: Boolean,
        now: Long = System.currentTimeMillis(),
    ): List<LocalPlaylist> {
        val favoriteId = playlists.firstOrNull { it.isFavorite }?.id ?: return playlists

        return if (shouldFavorite) {
            addSongs(playlists, favoriteId, listOf(songId), now)
        } else {
            removeSongs(playlists, favoriteId, listOf(songId), now)
        }
    }

    fun isFavorite(playlists: List<LocalPlaylist>, songId: String): Boolean =
        playlists.firstOrNull { it.isFavorite }?.songIds?.contains(songId) == true

    /** The favourites playlist, creating nothing. Null when the list has not been initialised. */
    fun favoritePlaylist(playlists: List<LocalPlaylist>): LocalPlaylist? =
        playlists.firstOrNull { it.isFavorite }
}
