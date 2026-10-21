package top.izuna.foliamajor.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * SAF-backed folder scanner.
 *
 * Replaces the desktop's File System Access API, which Android WebView does not implement. The tree
 * URI is persisted across restarts with `takePersistableUriPermission`, so the library survives a
 * reboot — the one thing the Capacitor packaging could never do.
 *
 * Recursion is bounded and symlink-safe by construction: `DocumentFile` walks the provider's tree
 * and cannot follow a link outside it.
 */
class LocalLibraryScanner(private val context: Context) {

    companion object {
        /** Guard against pathological trees (a mounted backup containing a nested copy of itself). */
        private const val MAX_DEPTH = 24

        /** The per-directory exclusion file, named after the desktop app's own convention. */
        const val FOLIA_IGNORE_FILE_NAME = ".foliaignore"
    }

    /**
     * Recursively lists a tree.
     *
     * Returns relative paths (POSIX separators) rather than URIs for everything the importer reasons
     * about, because the matching rules — sidecar lyrics, folder covers — are all path-shaped.
     */
    suspend fun scan(treeUri: Uri): List<ScannedFile> = withContext(Dispatchers.IO) {
        scanWithUris(treeUri).map { it.file }
    }

    /**
     * Scans and returns each file's URI alongside its metadata.
     *
     * The URI is captured during the walk because `DocumentFile.findFile` is a `ContentResolver`
     * query per path segment: resolving 2000 files one segment at a time cost more than the entire
     * tag-reading pass. The walk already holds every `DocumentFile`, so this is free.
     */
    suspend fun scanWithUris(treeUri: Uri): List<ScannedEntry> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val entries = mutableListOf<ScannedEntry>()

        /**
         * The `.foliaignore` stack, outermost first.
         *
         * Each directory may add one; its rules apply to that directory and everything below it.
         * The stack is passed down rather than re-read per file, so a deep tree costs one read per
         * directory that has a file, not one per file.
         */
        fun walk(directory: DocumentFile, prefix: String, depth: Int, matchers: List<FoliaIgnoreMatcher>) {
            if (depth > MAX_DEPTH) return

            val children = directory.listFiles()

            // A directory's own .foliaignore governs the entries inside it, so it is read before
            // they are visited.
            val ignoreFile = children.firstOrNull { it.isFile && it.name == FOLIA_IGNORE_FILE_NAME }
            val scopedMatchers = if (ignoreFile != null) {
                val contents = runCatching {
                    context.contentResolver.openInputStream(ignoreFile.uri)
                        ?.use { it.bufferedReader(Charsets.UTF_8).readText() }
                }.getOrNull()

                if (contents == null) {
                    matchers
                } else {
                    matchers + FoliaIgnoreMatcher.parse(contents, baseDirectory = prefix)
                }
            } else {
                matchers
            }

            for (child in children) {
                val name = child.name ?: continue
                // Hidden entries are skipped: a dot-directory is never music, and scanning
                // `.thumbnails` or `.git` is pure waste. `.foliaignore` is read above and must not
                // become a library entry, so it is skipped explicitly rather than by the dot rule.
                if (name.startsWith('.') || name == FOLIA_IGNORE_FILE_NAME) continue

                val relativePath = if (prefix.isEmpty()) name else "$prefix/$name"
                val isDirectory = child.isDirectory

                // An ignored directory is not descended into, which is what makes `cache/` exclude
                // everything inside it without listing each file.
                if (isIgnoredByFoliaMatchers(scopedMatchers, relativePath, isDirectory)) continue

                if (isDirectory) {
                    walk(child, relativePath, depth + 1, scopedMatchers)
                } else if (child.isFile) {
                    entries.add(
                        ScannedEntry(
                            file = ScannedFile(
                                relativePath = relativePath,
                                name = name,
                                sizeBytes = child.length(),
                                lastModified = child.lastModified(),
                            ),
                            uri = child.uri,
                        )
                    )
                }
            }
        }

        walk(root, "", 0, emptyList())
        entries
    }

    /**
     * Resolves a relative path back to a document URI.
     *
     * Only used for one-off lookups (reading a lyric at play time). Bulk work should use the URIs
     * returned by [scanWithUris] instead — this walks from the root one segment at a time.
     */
    suspend fun resolveFile(treeUri: Uri, relativePath: String): Uri? = withContext(Dispatchers.IO) {
        var current = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext null

        for (segment in relativePath.split('/')) {
            current = current.findFile(segment) ?: return@withContext null
        }

        current.uri.takeIf { current.isFile }
    }

    /**
     * Reads a file's tags.
     *
     * `MediaMetadataRetriever` is the platform decoder's tag reader, so it understands exactly the
     * formats the platform can play. The desktop app uses `music-metadata` instead, which covers more
     * containers — that difference is why [ANDROID_PLAYABLE_AUDIO_EXTENSIONS] is narrower than
     * [KNOWN_AUDIO_EXTENSIONS].
     *
     * Every field is optional: an untagged file returns nulls and the caller falls back to the
     * filename, which is the same chain the desktop app uses.
     */
    suspend fun readMetadata(uri: Uri): AudioTags = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            fun read(key: Int): String? =
                retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }

            AudioTags(
                title = read(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = read(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = read(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = read(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                trackNumber = read(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.let { parseLeadingInt(it) },
                discNumber = read(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                    ?.let { parseLeadingInt(it) },
                durationMs = read(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                bitrate = read(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull(),
                mimeType = read(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
            )
        } catch (error: Exception) {
            // A corrupt or unsupported file must not abort the scan; it imports as filename-only.
            AudioTags()
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Reads a text file (a sidecar lyric) as UTF-8. */
    suspend fun readText(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream: InputStream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            }
        }.getOrNull()
    }

    /** Reads a binary file (an embedded cover) into memory. */
    suspend fun readBytes(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
    }

    /**
     * Keeps read access across process death.
     *
     * Without this the tree URI is valid only for the current process, and the library silently
     * empties after a reboot — the exact failure mode the desktop web build has to ask the user to
     * re-authorise.
     */
    fun persistPermission(treeUri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        true
    }.getOrDefault(false)

    /** The persisted tree URIs this app still holds a grant for. */
    fun persistedTrees(): List<Uri> =
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri }

    /** Checks that a stored tree is still readable, e.g. after the folder was moved or deleted. */
    suspend fun isTreeAccessible(treeUri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            DocumentFile.fromTreeUri(context, treeUri)?.isDirectory == true
        }.getOrDefault(false)
    }
}

/**
 * A scanned file plus the URI the walk already had in hand.
 *
 * The importer needs both: the path drives all the matching rules, and the URI is what actually
 * opens the file. Recovering the URI later means a `findFile` query per path segment, which for a
 * whole library costs more than reading the tags.
 */
data class ScannedEntry(
    val file: ScannedFile,
    val uri: Uri,
)

/** Tags read from an audio file. Nulls mean "the tag was absent", never "empty string". */
data class AudioTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationMs: Long? = null,
    val bitrate: Long? = null,
    val mimeType: String? = null,
)

/**
 * Parses the leading integer of a tag.
 *
 * `MediaMetadataRetriever` reports track numbers as "3/12" on some files and "3" on others, so the
 * numerator is taken. The match must start at the beginning: a non-numeric tag like "A1" (common on
 * vinyl rips) yields null rather than 1, which would sort every such track into position 1.
 */
internal fun parseLeadingInt(value: String): Int? =
    Regex("""^\s*(\d+)""").find(value)?.groupValues?.get(1)?.toIntOrNull()

/** Whether the tree URI still points at something readable, without throwing. */
internal fun treeDocumentId(treeUri: Uri): String? = runCatching {
    DocumentsContract.getTreeDocumentId(treeUri)
}.getOrNull()
