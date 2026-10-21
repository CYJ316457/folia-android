package top.izuna.foliamajor.local

import android.content.Context
import top.izuna.foliamajor.playback.parseGainTag
import top.izuna.foliamajor.playback.parsePeakTag
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Imports a folder tree into the local library index.
 *
 * The pipeline is: scan the tree, derive the plan (which lyric and cover belongs to which track),
 * diff it against the stored index, read tags only for files whose audio actually changed, then
 * write one snapshot.
 *
 * The diff is what keeps a rescan cheap. Reading tags costs ~5–20 ms per file, so re-reading all of
 * them on every import is the difference between a 2000-track rescan taking half a minute and taking
 * under a second when only a handful of files moved. See [diffScan] for the rules.
 */
class LocalLibraryImporter(
    private val context: Context,
    private val scanner: LocalLibraryScanner = LocalLibraryScanner(context),
    private val store: LocalLibraryStore = LocalLibraryStore(context),
    private val covers: LocalCoverStore = LocalCoverStore(context),
) {

    /** Progress reported during an import, for the UI to show a count rather than a spinner. */
    data class Progress(
        val phase: Phase,
        val processed: Int,
        val total: Int,
    ) {
        enum class Phase { SCANNING, DIFFING, READING_TAGS, SAVING, DONE }
    }

    /**
     * Imports (or re-imports) a tree.
     *
     * Re-importing the same tree replaces that root's songs and leaves other roots alone, which is
     * what the desktop app does: importing a folder must not silently drop a second library.
     */
    suspend fun importTree(
        treeUri: Uri,
        onProgress: (Progress) -> Unit = {},
    ): Result<ImportSummary> = withContext(Dispatchers.IO) {
        runCatching {
            scanner.persistPermission(treeUri)

            onProgress(Progress(Progress.Phase.SCANNING, 0, 0))
            // URIs are captured during the walk: resolving them afterwards costs a content-resolver
            // query per path segment, which for a whole library exceeds the tag-reading time.
            val entries = scanner.scanWithUris(treeUri)
            val files = entries.map { it.file }
            val plan = buildScanPlan(files)

            // Only files the platform can actually decode become songs. The rest are reported so the
            // UI can say "3 files skipped" instead of silently showing a smaller library.
            val playable = plan.audioFiles.filter { isPlayableOnAndroid(it.name) }
            val skipped = plan.audioFiles.size - playable.size

            val existing = store.load()
            val rootKey = treeUri.toString()
            val keptFromOtherRoots = existing.songs.filterNot { it.rootUri == rootKey }
            val existingForRoot = existing.songs.filter { it.rootUri == rootKey }

            onProgress(Progress(Progress.Phase.DIFFING, 0, playable.size))

            val playablePlan = plan.copy(audioFiles = playable)
            val diff = diffScan(
                existingSongs = existingForRoot.map { song ->
                    SongSnapshot(
                        relativePath = song.relativePath,
                        sizeBytes = song.sizeBytes,
                        lastModified = song.lastModified,
                        sidecarLyricPath = song.sidecarLyricPath,
                        sidecarLyricFormat = song.sidecarLyricFormat,
                        translationLyricPath = song.translationLyricPath,
                        folderCoverPath = song.folderCoverPath,
                        embeddedMetadataVersion = song.embeddedMetadataVersion,
                    )
                },
                plan = playablePlan,
            )

            val uriByPath = entries.associate { it.file.relativePath to it.uri }
            val storedByPath = existingForRoot.associateBy { it.relativePath }

            val now = System.currentTimeMillis()
            val rebuilt = mutableListOf<LocalLibraryStore.SongRecord>()
            val counter = AtomicInteger(0)
            val total = playable.size

            // Referenced cover ids, collected so orphans can be reclaimed after the write.
            val referencedCoverIds = mutableSetOf<String>()

            // New or audio-changed files: read every tag and rebuild the record.
            for (decision in diff.toRead) {
                val file = decision.file
                val uri = uriByPath[file.relativePath]
                val tags = uri?.let { scanner.readMetadata(it) } ?: AudioTags()

                val filenameParts = parseFilenameParts(file.name)
                val metadata = buildImportedMetadata(
                    fileName = file.name,
                    embeddedTitle = tags.title,
                    fallbackTitle = filenameParts.title,
                    embeddedArtist = tags.artist,
                    embeddedAlbum = tags.album,
                    fallbackArtist = filenameParts.artist,
                )

                val lyric = plan.lyricFor(file)
                val coverUri = plan.coverFor(file)?.let { uriByPath[it] }
                val coverAssetId = extractCoverId(uri, coverUri)
                coverAssetId?.let { referencedCoverIds.add(it) }

                // ReplayGain is not exposed by any Android metadata API, so the ID3 tag is read
                // directly. Only the first megabyte is fetched: the tag lives at the head of the
                // file and reading the whole track would cost more than the tag scan.
                val replayGain = uri?.let { readReplayGain(it) }

                rebuilt.add(
                    LocalLibraryStore.SongRecord(
                        id = localSongId(file.relativePath),
                        relativePath = file.relativePath,
                        rootUri = rootKey,
                        fileName = file.name,
                        title = metadata.title,
                        titleSource = metadata.titleSource.name,
                        artistNames = metadata.artistNames,
                        albumName = metadata.albumName,
                        durationMs = tags.durationMs ?: 0L,
                        sizeBytes = file.sizeBytes,
                        lastModified = file.lastModified,
                        trackNumber = tags.trackNumber,
                        discNumber = tags.discNumber,
                        bitrate = tags.bitrate,
                        mimeType = tags.mimeType,
                        replayGainTrackDb = replayGain?.trackDb,
                        replayGainTrackPeak = replayGain?.trackPeak,
                        replayGainAlbumDb = replayGain?.albumDb,
                        replayGainAlbumPeak = replayGain?.albumPeak,
                        embeddedMetadataVersion = EMBEDDED_METADATA_VERSION,
                        sidecarLyricPath = lyric?.relativePath,
                        sidecarLyricFormat = lyric?.format,
                        coverAssetId = coverAssetId,
                        translationLyricPath = plan.translationLyricFor(file),
                        folderCoverPath = plan.coverFor(file),
                        // Preserve the original import time so "added" ordering survives a rescan.
                        addedAt = storedByPath[file.relativePath]?.addedAt ?: now,
                    )
                )

                onProgress(Progress(Progress.Phase.READING_TAGS, counter.incrementAndGet(), total))
            }

            // Audio unchanged but the sidecar links moved: keep the tags, patch the paths.
            for (decision in diff.toRelink) {
                val file = decision.file
                val stored = storedByPath[file.relativePath] ?: continue
                val lyric = plan.lyricFor(file)

                rebuilt.add(
                    stored.copy(
                        sidecarLyricPath = lyric?.relativePath,
                        sidecarLyricFormat = lyric?.format,
                        translationLyricPath = plan.translationLyricFor(file),
                        folderCoverPath = plan.coverFor(file),
                        // The relink path reuses the stored tags, so it can only claim the current
                        // version when the record already had it. Claiming it here would mark an
                        // outdated record as current and permanently skip its re-read.
                        embeddedMetadataVersion = stored.embeddedMetadataVersion,
                    )
                )

                onProgress(Progress(Progress.Phase.READING_TAGS, counter.incrementAndGet(), total))
            }

            // Nothing changed: reuse the stored record untouched.
            for (decision in diff.unchanged) {
                storedByPath[decision.file.relativePath]?.let { rebuilt.add(it) }
                onProgress(Progress(Progress.Phase.READING_TAGS, counter.incrementAndGet(), total))
            }

            onProgress(Progress(Progress.Phase.SAVING, total, total))

            // Assign the rebuilt songs to artist/album entities. Only the songs in this root are
            // passed, so importing one folder never rewrites another folder's assignments.
            val existingEntities = existing.entities.map { it.toModel() }
            val existingAssignments = existing.assignments.map { it.toModel() }
            val catalog = assignImportedSongs(
                songs = rebuilt,
                existingEntities = existingEntities,
                existingAssignments = existingAssignments,
                preserveNonImportAssignments = true,
            )

            // Drop assignments for songs that no longer exist, so the graph does not grow forever.
            val liveSongIds = (keptFromOtherRoots + rebuilt).map { it.id }.toSet()
            val liveAssignments = catalog.assignments.filter { it.songId in liveSongIds }

            val roots = existing.roots.filterNot { it.uri == rootKey } + LocalLibraryStore.RootRecord(
                uri = rootKey,
                displayName = treeDisplayName(treeUri),
                addedAt = existing.roots.firstOrNull { it.uri == rootKey }?.addedAt ?: now,
            )

            val snapshot = LocalLibraryStore.LibrarySnapshot(
                roots = roots,
                songs = keptFromOtherRoots + rebuilt,
                entities = catalog.entities.map { it.toRecord() },
                assignments = liveAssignments.map { it.toRecord() },
                lyricFormatOrder = existing.lyricFormatOrder,
            )

            check(store.save(snapshot)) { "Failed to write the library index" }

            // Covers still referenced by songs from *other* roots must survive this import.
            val otherRootCoverIds = keptFromOtherRoots.mapNotNull { it.coverAssetId }
            referencedCoverIds.addAll(otherRootCoverIds)
            val reclaimed = covers.deleteUnreferenced(referencedCoverIds)
            if (reclaimed > 0) {
                println("[LocalLibrary] Reclaimed $reclaimed orphaned covers")
            }

            onProgress(Progress(Progress.Phase.DONE, total, total))

            ImportSummary(
                importedCount = rebuilt.size,
                skippedCount = skipped,
                totalFilesScanned = files.size,
                lyricCount = rebuilt.count { it.sidecarLyricPath != null },
                coverCount = rebuilt.count { it.folderCoverPath != null },
                addedCount = diff.toRead.size,
                relinkedCount = diff.toRelink.size,
                unchangedCount = diff.unchanged.size,
                removedCount = diff.removedPaths.size,
                tagReads = diff.toRead.size,
                newEntities = catalog.createdEntityIds.size,
                needsReview = catalog.entities.count { it.needsReview },
            )
        }
    }

    /** Removes a root and every song imported from it. */
    suspend fun removeRoot(treeUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val rootKey = treeUri.toString()
        val existing = store.load()
        val snapshot = existing.copy(
            roots = existing.roots.filterNot { it.uri == rootKey },
            songs = existing.songs.filterNot { it.rootUri == rootKey },
        )
        store.save(snapshot)
    }

    suspend fun load(): LocalLibraryStore.LibrarySnapshot = store.load()

    /**
     * Reads ReplayGain from the file's ID3 tag.
     *
     * Returns a 4-tuple of (trackDb, trackPeak, albumDb, albumPeak), or null when the tag has none.
     * The head of the file is read rather than the whole track: ID3v2 lives at the start, and a
     * 40 MB FLAC should not be pulled into memory to find four short strings.
     */
    private suspend fun readReplayGain(uri: Uri): ReplayGainQuad? {
        val head = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(ID3_HEAD_READ_BYTES)
                val read = stream.read(buffer)
                if (read <= 0) null else buffer.copyOf(read)
            }
        }.getOrNull() ?: return null

        val raw = Id3ReplayGainReader.read(head) ?: return null
        val quad = ReplayGainQuad(
            parseGainTag(raw.trackGain),
            parsePeakTag(raw.trackPeak),
            parseGainTag(raw.albumGain),
            parsePeakTag(raw.albumPeak),
        )
        return quad.takeUnless { it.isEmpty }
    }

    /**
     * Extracts a song's cover and returns its asset id, or null when there is none.
     *
     * A folder cover (`cover.png` beside the track) wins over the embedded one, matching the desktop
     * app: a user who drops `cover.jpg` into an album folder is overriding what the files say, and
     * that must hold even for a single track.
     *
     * Both sources go through the content-addressed store, so identical bytes from different tracks
     * collapse to one file automatically.
     */
    private suspend fun extractCoverId(
        audioUri: Uri?,
        folderCoverUri: Uri?,
    ): String? {
        if (audioUri == null && folderCoverUri == null) return null

        return runCatching {
            val payload = folderCoverUri
                ?.let { coverFromBytes(scanner.readBytes(it) ?: return@runCatching null) }
                ?: audioUri?.let { extractEmbeddedCover(context, it) }
                ?: return@runCatching null

            val assetId = coverAssetIdFor(payload.bytes)
            covers.write(assetId, payload)
            assetId
        }.getOrNull()
    }

    /**
     * A human-readable name for the tree.
     *
     * `DocumentFile.name` is the folder's own name, which is what the user recognises. The URI's
     * last path segment is percent-encoded (`primary%3AMusic`) and is only a fallback.
     */
    private fun treeDisplayName(treeUri: Uri): String =
        runCatching {
            android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                ?.substringAfterLast(':')
                ?.substringAfterLast('/')
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()
            ?: treeUri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
            ?: "Music"
}


data class ImportSummary(
    val importedCount: Int,
    val skippedCount: Int,
    val totalFilesScanned: Int,
    val lyricCount: Int,
    val coverCount: Int,
    /** Files that were new or whose audio changed. */
    val addedCount: Int = importedCount,
    /** Files whose tags were reused and only the sidecar links were refreshed. */
    val relinkedCount: Int = 0,
    val unchangedCount: Int = 0,
    /** Files that were in the index and are gone from disk. */
    val removedCount: Int = 0,
    /** How many tag reads the diff actually performed. Surfaced so the saving is visible. */
    val tagReads: Int = importedCount,
    /** Artist/album entities created during this import. */
    val newEntities: Int = 0,
    /** Entities the resolver could not disambiguate, now awaiting a manual merge. */
    val needsReview: Int = 0,
)

/** ReplayGain values parsed into numbers, in the shape the record stores. */
private data class ReplayGainQuad(
    val trackDb: Double?,
    val trackPeak: Double?,
    val albumDb: Double?,
    val albumPeak: Double?,
) {
    val isEmpty: Boolean
        get() = trackDb == null && trackPeak == null && albumDb == null && albumPeak == null
}

/**
 * How much of the file is read looking for the ID3 tag.
 *
 * ID3v2 sits at the start of the file, so the tag is always inside this window. Reading further
 * would mean pulling a whole track into memory to find four short strings.
 */
private const val ID3_HEAD_READ_BYTES = 256 * 1024
