package top.izuna.foliamajor.local

/**
 * Decides what a rescan actually has to do.
 *
 * Two separate costs dominate a rescan, and they are very different:
 *
 *  - Reading audio tags is expensive (~5–20 ms per file on a phone) and is only needed when the
 *    audio file itself changed.
 *  - Relinking sidecar paths is free, and is needed when a lyric or cover file appeared, vanished,
 *    or won a different format-priority contest.
 *
 * Conflating them is what makes a naive "rescan everything" import take 30 s on a 2000-track
 * library. Keeping them apart means dropping a `.lrc` next to a track costs one path comparison
 * instead of a full tag read.
 *
 * Kept pure (no Android types) so every rule is unit-testable.
 */

/** The subset of a stored song the diff needs. */
data class SongSnapshot(
    val relativePath: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val sidecarLyricPath: String?,
    val sidecarLyricFormat: String?,
    val translationLyricPath: String?,
    val folderCoverPath: String?,
    /**
     * Which extraction pass produced this record. A record from an older pass is re-read even when
     * the file is untouched, so a newly added field reaches an existing library.
     */
    val embeddedMetadataVersion: Int = 0,
)

/** What to do with one scanned audio file. */
data class FileDecision(
    val file: ScannedFile,
    val readTags: Boolean,
    val relink: Boolean,
)

data class ScanDiff(
    /** New or audio-changed files: tags must be read. */
    val toRead: List<FileDecision>,
    /** Audio unchanged, but a sidecar path changed: reuse the tags, update the links. */
    val toRelink: List<FileDecision>,
    /** Nothing changed at all: reuse the stored record verbatim. */
    val unchanged: List<FileDecision>,
    /** Relative paths that were in the index and are gone from disk. */
    val removedPaths: List<String>,
) {
    val total: Int get() = toRead.size + toRelink.size + unchanged.size
    val tagReadsSaved: Int get() = toRelink.size + unchanged.size
}

/**
 * Computes the diff.
 *
 * A sidecar path change counts as a relink, not a re-read, because the song's own metadata did not
 * move. This is what makes these cases cheap:
 *
 *  - A lyric file is added next to an already-imported album.
 *  - The user reorders lyric formats, changing which file wins for a track.
 *  - A folder cover is added or removed.
 *
 * A file whose mtime changed is always re-read even when its size did not: re-tagging in place
 * keeps the byte count identical.
 *
 * A file whose record predates the current [EMBEDDED_METADATA_VERSION] is also re-read, and that is
 * the only path by which a library picks up a field added in a later app version. Skipping it would
 * leave every existing song silently missing the new field until the user cleared the library.
 */
fun diffScan(
    existingSongs: List<SongSnapshot>,
    plan: ScanPlan,
    currentMetadataVersion: Int = EMBEDDED_METADATA_VERSION,
): ScanDiff {
    val existingByPath = existingSongs.associateBy { it.relativePath }
    val scannedPaths = plan.audioFiles.map { it.relativePath }.toSet()

    val toRead = mutableListOf<FileDecision>()
    val toRelink = mutableListOf<FileDecision>()
    val unchanged = mutableListOf<FileDecision>()

    for (file in plan.audioFiles) {
        val existing = existingByPath[file.relativePath]
        val lyric = plan.lyricFor(file)
        val translation = plan.translationLyricFor(file)
        val cover = plan.coverFor(file)

        if (existing == null) {
            toRead.add(FileDecision(file, readTags = true, relink = false))
            continue
        }

        val audioChanged = existing.sizeBytes != file.sizeBytes
            || existing.lastModified != file.lastModified
        val metadataOutdated = existing.embeddedMetadataVersion != currentMetadataVersion

        if (audioChanged || metadataOutdated) {
            // The tag read rebuilds every field, including the links, so no separate relink.
            toRead.add(FileDecision(file, readTags = true, relink = false))
            continue
        }

        val linksChanged = existing.sidecarLyricPath != lyric?.relativePath
            || existing.sidecarLyricFormat != lyric?.format
            || existing.translationLyricPath != translation
            || existing.folderCoverPath != cover

        if (linksChanged) {
            toRelink.add(FileDecision(file, readTags = false, relink = true))
        } else {
            unchanged.add(FileDecision(file, readTags = false, relink = false))
        }
    }

    val removedPaths = existingByPath.keys
        .filterNot { it in scannedPaths }
        .sorted()

    return ScanDiff(
        toRead = toRead,
        toRelink = toRelink,
        unchanged = unchanged,
        removedPaths = removedPaths,
    )
}
