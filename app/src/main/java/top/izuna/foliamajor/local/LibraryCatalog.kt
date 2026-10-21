package top.izuna.foliamajor.local

/**
 * Assigns imported songs to artist/album entities, ported from folia-major's
 * `src/services/localLibraryImportCatalog.ts`.
 *
 * Two pieces of context do the heavy lifting, and both exist to stop one album from fragmenting:
 *
 *  - **Album context key** = root + relative folder + album name. Songs in the same folder with the
 *    same album tag share one album entity, even when their artist tags differ (a compilation).
 *  - **Artist overlap** = when a song has no album context yet, the artists it shares with an
 *    existing album's songs are used to find that album. This is what keeps the second track of a
 *    compilation from creating a second album entity.
 *
 * Re-import preserves non-import origins: a user's manual merge or split is never undone by a rescan.
 */
data class AssignmentResult(
    val entities: List<LibraryEntity>,
    val assignments: List<LibraryAssignment>,
    /** Entity ids created during this pass, for logging and for the UI's "new artists" count. */
    val createdEntityIds: List<String>,
)

/**
 * Assigns a batch of songs.
 *
 * `existingEntities` / `existingAssignments` are the current graph. The function returns the whole
 * updated graph rather than mutating in place, because the caller persists it as one snapshot.
 */
fun assignImportedSongs(
    songs: List<LocalLibraryStore.SongRecord>,
    existingEntities: List<LibraryEntity>,
    existingAssignments: List<LibraryAssignment>,
    /** When true, an assignment whose origin is not IMPORT is kept as-is. */
    preserveNonImportAssignments: Boolean = true,
    now: Long = System.currentTimeMillis(),
    createId: () -> String = { EntityIdGenerator.next() },
): AssignmentResult {
    if (songs.isEmpty()) {
        return AssignmentResult(existingEntities, existingAssignments, emptyList())
    }

    val entities = existingEntities.toMutableList()
    val assignmentBySongId = existingAssignments.associateBy { it.songId }
    val nextAssignments = mutableListOf<LibraryAssignment>()

    // Album entity id per import context, so a compilation's tracks share one album.
    val albumContext = mutableMapOf<String, String>()

    // Seed the context map from assignments that already have an album, so a rescan lands on the
    // same entities the first import created instead of minting new ones.
    for (assignment in existingAssignments) {
        val albumId = assignment.albumEntityId ?: continue
        val song = songs.firstOrNull { it.id == assignment.songId } ?: continue
        val albumName = song.albumName ?: continue
        albumContext[albumImportContextKey(song, albumName)] = albumId
    }

    val createdIds = mutableListOf<String>()

    for (song in songs) {
        val current = assignmentBySongId[song.id]
        val preserveArtist = preserveNonImportAssignments && current != null &&
            current.artistOrigin != AssignmentOrigin.IMPORT
        val preserveAlbum = preserveNonImportAssignments && current != null &&
            current.albumOrigin != AssignmentOrigin.IMPORT

        val albumName = cleanLocalLibraryName(song.albumName)

        // --- Album ---------------------------------------------------------------------------
        var albumId = if (preserveAlbum) current?.albumEntityId else null

        if (!preserveAlbum && albumName != null) {
            val contextKey = albumImportContextKey(song, albumName)
            val normalizedArtistNames = song.artistNames
                .map { normalizeLocalLibraryName(it) }
                .toSet()

            // Albums whose existing songs share an artist with this one. A single such album is a
            // strong signal; two or more is ambiguous and is left to the context key.
            val overlapAlbumIds = existingAssignments.asSequence()
                .mapNotNull { it.albumEntityId }
                .distinct()
                .filter { candidateAlbumId ->
                    existingAssignments.any { assignment ->
                        assignment.albumEntityId == candidateAlbumId &&
                            assignment.artistEntityIds.any { artistId ->
                                val artist = entities.firstOrNull { it.id == artistId } ?: return@any false
                                artist.normalizedAliases.any { it in normalizedArtistNames }
                            }
                    }
                }
                .toList()

            val preferredId = albumContext[contextKey]
                ?: current?.albumEntityId
                ?: overlapAlbumIds.singleOrNull()

            val before = entities.size
            val resolved = resolveLibraryEntityNames(
                entities = entities,
                kind = EntityKind.ALBUM,
                names = listOf(albumName),
                preferredEntityIds = listOfNotNull(preferredId),
                now = now,
                createId = createId,
            )
            if (entities.size > before) createdIds.add(entities.last().id)

            albumId = resolved.firstOrNull()
            if (albumId != null) albumContext[contextKey] = albumId
        }

        // --- Artists -------------------------------------------------------------------------
        // Context for disambiguating an artist name: the other artists already credited on the same
        // album. A song credited "A; B" makes B's next appearance resolvable.
        val albumArtistContext = if (albumId != null) {
            (existingAssignments + nextAssignments)
                .filter { it.albumEntityId == albumId }
                .flatMap { it.artistEntityIds }
        } else {
            emptyList()
        }

        val artistIds = if (preserveArtist) {
            current?.artistEntityIds.orEmpty()
        } else {
            val before = entities.size
            val resolved = resolveLibraryEntityNames(
                entities = entities,
                kind = EntityKind.ARTIST,
                names = song.artistNames,
                preferredEntityIds = current?.artistEntityIds?.takeIf { it.isNotEmpty() }
                    ?: albumArtistContext,
                now = now,
                createId = createId,
            )
            // Every entity appended during this call is new; record them for the caller's summary.
            for (index in before until entities.size) createdIds.add(entities[index].id)
            resolved
        }

        nextAssignments.add(
            LibraryAssignment(
                songId = song.id,
                artistEntityIds = artistIds,
                artistOrigin = if (preserveArtist) current?.artistOrigin ?: AssignmentOrigin.IMPORT else AssignmentOrigin.IMPORT,
                albumEntityId = albumId,
                albumOrigin = if (preserveAlbum) current?.albumOrigin ?: AssignmentOrigin.IMPORT else AssignmentOrigin.IMPORT,
                updatedAt = now,
            )
        )
    }

    // Songs not in this batch keep their existing assignments, minus any this batch replaced.
    val batchIds = songs.map { it.id }.toSet()
    val kept = existingAssignments.filterNot { it.songId in batchIds }

    return AssignmentResult(
        entities = entities,
        assignments = kept + nextAssignments,
        createdEntityIds = createdIds.distinct(),
    )
}

/**
 * The key that decides whether two songs belong to the same album.
 *
 * Root + relative folder + album name, so two folders that happen to contain the same album title
 * (a "Greatest Hits" in two places) stay separate unless the user merges them.
 */
fun albumImportContextKey(song: LocalLibraryStore.SongRecord, albumName: String): String {
    val root = normalizeLocalLibraryName(song.rootUri.substringAfterLast('/'))
    val parent = normalizeLocalLibraryName(parentRelativePath(song.relativePath))
    return listOf(root, parent, normalizeLocalLibraryName(albumName)).joinToString("\u0000")
}

/** Removes a song's assignment, used when a file disappears from disk. */
fun removeSongAssignments(
    assignments: List<LibraryAssignment>,
    songIds: Set<String>,
): List<LibraryAssignment> = assignments.filterNot { it.songId in songIds }
