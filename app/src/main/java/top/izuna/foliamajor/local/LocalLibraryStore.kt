package top.izuna.foliamajor.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Local library persistence.
 *
 * A single JSON file rather than Room. The library is a flat list of a few thousand records read
 * once at startup and rewritten after an import; that is the access pattern SQLite is worst at
 * paying for and a serialized list is best at. Room earns its keep when the app queries subsets
 * (filter by artist, sort by album) and mutates individual rows — that is what the next stage adds,
 * along with the artist/album entity tables. Introducing Room now would mean writing the migration
 * for a schema that is about to change shape.
 *
 * Writes go through a temp file and a rename so a crash mid-write cannot truncate the library.
 */
class LocalLibraryStore(private val context: Context) {

    @Serializable
    data class RootRecord(
        val uri: String,
        val displayName: String,
        val addedAt: Long,
    )

    @Serializable
    data class SongRecord(
        val id: String,
        val relativePath: String,
        val rootUri: String,
        val fileName: String,
        val title: String,
        val titleSource: String,
        val artistNames: List<String>,
        val albumName: String? = null,
        val durationMs: Long = 0L,
        val sizeBytes: Long = 0L,
        val lastModified: Long = 0L,
        val trackNumber: Int? = null,
        val discNumber: Int? = null,
        val bitrate: Long? = null,
        val mimeType: String? = null,
        /** ReplayGain read from the file's ID3 tag at import time. */
        val replayGainTrackDb: Double? = null,
        val replayGainTrackPeak: Double? = null,
        val replayGainAlbumDb: Double? = null,
        val replayGainAlbumPeak: Double? = null,
        /**
         * Which extraction pass produced this record's embedded metadata.
         *
         * Bumping [EMBEDDED_METADATA_VERSION] forces every older record to be re-read on the next
         * scan. Without it, adding a new extracted field leaves existing songs permanently missing
         * that field, because the incremental diff only looks at file size and mtime.
         */
        val embeddedMetadataVersion: Int = 0,
        val sidecarLyricPath: String? = null,
        val sidecarLyricFormat: String? = null,
        /** `sha256:<hex>` of the song's cover, stored in the content-addressed cover store. */
        val coverAssetId: String? = null,
        val translationLyricPath: String? = null,
        val folderCoverPath: String? = null,
        val addedAt: Long = 0L,
    )

    @Serializable
    data class EntityRecord(
        val id: String,
        val kind: String,
        val displayName: String,
        val aliases: List<String> = emptyList(),
        val normalizedAliases: List<String> = emptyList(),
        val mergedInto: String? = null,
        val needsReview: Boolean = false,
        val createdAt: Long = 0L,
        val updatedAt: Long = 0L,
    )

    @Serializable
    data class AssignmentRecord(
        val songId: String,
        val artistEntityIds: List<String> = emptyList(),
        val artistOrigin: String = "IMPORT",
        val albumEntityId: String? = null,
        val albumOrigin: String = "IMPORT",
        val updatedAt: Long = 0L,
    )

    @Serializable
    data class PlaylistRecord(
        val id: String,
        val name: String,
        val songIds: List<String> = emptyList(),
        val createdAt: Long = 0L,
        val updatedAt: Long = 0L,
        val isFavorite: Boolean = false,
    )

    @Serializable
    data class LibrarySnapshot(
        val version: Int = SNAPSHOT_VERSION,
        val roots: List<RootRecord> = emptyList(),
        val songs: List<SongRecord> = emptyList(),
        val entities: List<EntityRecord> = emptyList(),
        val assignments: List<AssignmentRecord> = emptyList(),
        val playlists: List<PlaylistRecord> = emptyList(),
        val lyricFormatOrder: List<String> = LOCAL_LYRIC_FILE_FORMATS,
    )

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val storeFile: File
        get() = File(context.filesDir, "local-library.json")

    suspend fun load(): LibrarySnapshot = withContext(Dispatchers.IO) {
        val file = storeFile
        if (!file.exists()) return@withContext LibrarySnapshot()

        runCatching {
            json.decodeFromString<LibrarySnapshot>(file.readText())
        }.getOrElse {
            // A corrupt snapshot is treated as "no library" rather than a crash loop. The audio is
            // still on disk, so the user re-imports and nothing is lost but the scan time.
            println("[LocalLibrary] Failed to read snapshot: $it")
            LibrarySnapshot()
        }
    }

    suspend fun save(snapshot: LibrarySnapshot): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val target = storeFile
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.writeText(json.encodeToString(snapshot))

            // Rename is atomic on the same filesystem; a partial write leaves the old file intact.
            if (target.exists()) target.delete()
            temp.renameTo(target)
        }.onFailure { println("[LocalLibrary] Failed to write snapshot: $it") }
            .isSuccess
    }

    suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        runCatching { storeFile.delete() }.getOrDefault(false)
    }
}

/** Record → model. Unknown enum names fall back rather than throwing, so an old snapshot still loads. */
fun LocalLibraryStore.EntityRecord.toModel(): LibraryEntity = LibraryEntity(
    id = id,
    kind = runCatching { EntityKind.valueOf(kind) }.getOrDefault(EntityKind.ARTIST),
    displayName = displayName,
    aliases = aliases,
    normalizedAliases = normalizedAliases,
    mergedInto = mergedInto,
    needsReview = needsReview,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun LibraryEntity.toRecord(): LocalLibraryStore.EntityRecord = LocalLibraryStore.EntityRecord(
    id = id,
    kind = kind.name,
    displayName = displayName,
    aliases = aliases,
    normalizedAliases = normalizedAliases,
    mergedInto = mergedInto,
    needsReview = needsReview,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun LocalLibraryStore.AssignmentRecord.toModel(): LibraryAssignment = LibraryAssignment(
    songId = songId,
    artistEntityIds = artistEntityIds,
    artistOrigin = runCatching { AssignmentOrigin.valueOf(artistOrigin) }.getOrDefault(AssignmentOrigin.IMPORT),
    albumEntityId = albumEntityId,
    albumOrigin = runCatching { AssignmentOrigin.valueOf(albumOrigin) }.getOrDefault(AssignmentOrigin.IMPORT),
    updatedAt = updatedAt,
)

fun LibraryAssignment.toRecord(): LocalLibraryStore.AssignmentRecord = LocalLibraryStore.AssignmentRecord(
    songId = songId,
    artistEntityIds = artistEntityIds,
    artistOrigin = artistOrigin.name,
    albumEntityId = albumEntityId,
    albumOrigin = albumOrigin.name,
    updatedAt = updatedAt,
)

private const val SNAPSHOT_VERSION = 1

/**
 * Version of the embedded-metadata extraction.
 *
 * Bump this whenever the importer starts reading something new from the file (a tag, a cover, a
 * gain). Every song whose stored version is lower is re-read on the next scan, which is the only way
 * an existing library picks up a newly added field.
 *
 * History:
 *  1 — title/artist/album/track/disc/duration/bitrate
 *  2 — sidecar lyric paths
 *  3 — embedded cover (content-addressed)
 *  4 — ReplayGain from ID3 TXXX frames
 */
const val EMBEDDED_METADATA_VERSION = 4

fun LocalLibraryStore.PlaylistRecord.toModel(): LocalPlaylist = LocalPlaylist(
    id = id,
    name = name,
    songIds = songIds,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isFavorite = isFavorite,
)

fun LocalPlaylist.toRecord(): LocalLibraryStore.PlaylistRecord = LocalLibraryStore.PlaylistRecord(
    id = id,
    name = name,
    songIds = songIds,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isFavorite = isFavorite,
)
