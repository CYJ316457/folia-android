package top.izuna.foliamajor.local

/**
 * Turns a flat list of scanned files into the import plan, ported from the diffing half of
 * folia-major's `src/services/localMusicService.ts`.
 *
 * Kept as pure logic with no Android types so the matching rules — which lyric file belongs to
 * which track, which folder cover wins — are unit-testable without a device or a real SAF tree.
 */

/** One file found by the scanner. */
data class ScannedFile(
    val relativePath: String,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

/** A sidecar lyric file that won the format-priority contest for its base path. */
data class LyricCandidate(
    val relativePath: String,
    val format: String,
)

/** Everything the importer needs, derived from one pass over the scanned files. */
data class ScanPlan(
    val audioFiles: List<ScannedFile>,
    /** Keyed by [sidecarLyricBasePath] of the audio file. */
    val lyricsByBasePath: Map<String, LyricCandidate>,
    /** Keyed by [sidecarLyricBasePath]; only `.t.lrc` / `.t.vtt` exist in the desktop app. */
    val translationLyricsByBasePath: Map<String, String>,
    /** Keyed by parent relative path. */
    val folderCoversByParent: Map<String, String>,
) {
    val skippedUnplayable: List<ScannedFile>
        get() = audioFiles.filterNot { isPlayableOnAndroid(it.name) }
}

/**
 * Builds the plan.
 *
 * Two rules decide the result and both come from the desktop implementation:
 *
 *  - A track's sidecar lyric is chosen by format priority, not by directory order. `track.lrc` beats
 *    `track.krc` even when the krc sorts first, because LRC is the format the user's order puts
 *    first. Ties are broken by path so the result is stable across scans.
 *  - A folder cover is chosen by name priority (`cover.png` > `cover.jpg` > `cover.jpeg`), and it
 *    applies to that directory only — a cover never leaks into subdirectories.
 */
fun buildScanPlan(
    files: List<ScannedFile>,
    lyricFormatOrder: List<String> = LOCAL_LYRIC_FILE_FORMATS,
): ScanPlan {
    val order = normalizeLocalLyricFormatOrder(lyricFormatOrder)

    val audioFiles = files.filter { isKnownAudioFile(it.name) }

    val lyricsByBasePath = mutableMapOf<String, LyricCandidate>()
    val translationLyricsByBasePath = mutableMapOf<String, String>()
    val folderCoversByParent = mutableMapOf<String, String>()

    // Group lyric files by their base path first, so the winner is chosen per track rather than
    // globally — two tracks in one folder can each have their own krc and lrc.
    val lyricGroups = mutableMapOf<String, MutableList<ScannedFile>>()
    val translationGroups = mutableMapOf<String, MutableList<ScannedFile>>()
    val coverGroups = mutableMapOf<String, MutableList<ScannedFile>>()

    for (file in files) {
        when {
            isTranslationLyricFile(file.name) -> {
                val key = sidecarLyricBasePath(file.relativePath, isTranslation = true)
                translationGroups.getOrPut(key) { mutableListOf() }.add(file)
            }

            isLyricFile(file.name) -> {
                val key = sidecarLyricBasePath(file.relativePath, isTranslation = false)
                lyricGroups.getOrPut(key) { mutableListOf() }.add(file)
            }

            PREFERRED_FOLDER_COVER_FILES.any { it.equals(file.name, ignoreCase = true) } -> {
                val parent = parentRelativePath(file.relativePath)
                coverGroups.getOrPut(parent) { mutableListOf() }.add(file)
            }
        }
    }

    for ((key, group) in lyricGroups) {
        val winner = group.minWithOrNull(
            compareBy({ localLyricFilePriority(it.name, order) }, { it.relativePath })
        ) ?: continue
        lyricsByBasePath[key] = LyricCandidate(
            relativePath = winner.relativePath,
            format = winner.name.substringAfterLast('.', "").lowercase(),
        )
    }

    for ((key, group) in translationGroups) {
        val winner = group.minWithOrNull(
            compareBy({ localLyricFilePriority(it.name, order) }, { it.relativePath })
        ) ?: continue
        translationLyricsByBasePath[key] = winner.relativePath
    }

    for ((parent, group) in coverGroups) {
        val winner = preferredFolderCover(group.map { it.name }) ?: continue
        folderCoversByParent[parent] = group.first { it.name.equals(winner, ignoreCase = true) }.relativePath
    }

    return ScanPlan(
        audioFiles = audioFiles.sortedBy { it.relativePath },
        lyricsByBasePath = lyricsByBasePath,
        translationLyricsByBasePath = translationLyricsByBasePath,
        folderCoversByParent = folderCoversByParent,
    )
}

/** The lyric file that belongs to an audio file, or null when the track has none. */
fun ScanPlan.lyricFor(audio: ScannedFile): LyricCandidate? =
    lyricsByBasePath[sidecarLyricBasePath(audio.relativePath, isTranslation = false)]

fun ScanPlan.translationLyricFor(audio: ScannedFile): String? =
    translationLyricsByBasePath[sidecarLyricBasePath(audio.relativePath, isTranslation = false)]

fun ScanPlan.coverFor(audio: ScannedFile): String? =
    folderCoversByParent[parentRelativePath(audio.relativePath)]

/**
 * A stable identity for a track across rescans.
 *
 * Derived from the relative path rather than the content hash: hashing every file on every scan is
 * the one thing that makes a large library's rescan slow, and a moved file is legitimately a new
 * entry (its sidecar lyrics and cover move with it, but its play history does not).
 */
fun localSongId(relativePath: String): String = relativePath

/**
 * Whether a rescanned file differs from its stored record.
 *
 * Size and mtime together catch the realistic edits (re-tagged, re-encoded, truncated) without
 * reading a byte. A file whose mtime changed but size did not still counts as changed, because
 * re-tagging in place keeps the size identical.
 */
fun hasFileChanged(
    previous: ScannedFile?,
    current: ScannedFile,
): Boolean = previous == null
    || previous.sizeBytes != current.sizeBytes
    || previous.lastModified != current.lastModified
