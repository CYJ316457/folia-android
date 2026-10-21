package top.izuna.foliamajor.local

/**
 * Metadata resolution, ported from folia-major's `src/utils/localSongMetadata.ts`.
 *
 * The title fallback chain is the whole point of this file: embedded tag, then filename-derived
 * title, then the filename stem, then the raw filename. Getting the order wrong is very visible —
 * a library full of "01 - Track.mp3" instead of the tagged titles — so it is ported exactly.
 */

/** Where a song's title came from. The UI shows this so a wrong title is traceable to its source. */
enum class TitleSource { EMBEDDED, FILENAME }

/** The metadata captured at import time. Online matches are kept separately and never overwrite it. */
data class ImportedMetadata(
    val title: String,
    val titleSource: TitleSource,
    val artistNames: List<String>,
    val albumName: String?,
)

/**
 * Builds the import-time metadata snapshot.
 *
 * `fallbackTitle` is the filename-derived title (the desktop app computes it from a `title - artist`
 * filename pattern); `embeddedTitle` is the tag. Embedded always wins when present and non-blank.
 *
 * Artist names go through [splitLocalLibraryArtistNames] on both paths, so a tag written as
 * "A; B" and a filename written as "A/B" produce the same two artists.
 */
fun buildImportedMetadata(
    fileName: String,
    embeddedTitle: String? = null,
    fallbackTitle: String? = null,
    embeddedArtist: String? = null,
    embeddedArtists: List<String> = emptyList(),
    fallbackArtist: String? = null,
    embeddedAlbum: String? = null,
    fallbackAlbum: String? = null,
): ImportedMetadata {
    val normalizedEmbeddedTitle = cleanLocalLibraryName(embeddedTitle)

    val title = normalizedEmbeddedTitle
        ?: cleanLocalLibraryName(fallbackTitle)
        ?: stripLocalAudioExtension(fileName)
            .takeIf { it.isNotEmpty() }
        ?: fileName

    val embeddedArtistNames = embeddedArtists.flatMap { splitLocalLibraryArtistNames(it) }

    return ImportedMetadata(
        title = title,
        titleSource = if (normalizedEmbeddedTitle != null) TitleSource.EMBEDDED else TitleSource.FILENAME,
        artistNames = if (embeddedArtistNames.isNotEmpty()) {
            embeddedArtistNames
        } else {
            splitLocalLibraryArtistNames(embeddedArtist ?: fallbackArtist)
        },
        albumName = cleanLocalLibraryName(embeddedAlbum ?: fallbackAlbum),
    )
}

/**
 * Splits a `Artist - Title` or `Title - Artist` filename into its parts.
 *
 * Which side is the artist is genuinely ambiguous, and getting it backwards swaps every title in the
 * library. The desktop app resolves it from the presence of a track number and the tag data it
 * already read, so this only runs as a fallback when tags are absent; it prefers the convention that
 * the leading segment is the artist when a numeric prefix was stripped by the caller.
 */
data class FilenameParts(val title: String, val artist: String?)

/**
 * Extracts a title and optional artist from a filename stem.
 *
 * Only an en-dash or a spaced hyphen separates the parts. A bare `-` inside a word ("Jay-Z") is
 * left alone, which is why the separator requires surrounding spaces.
 */
fun parseFilenameParts(fileName: String): FilenameParts {
    val stem = stripLocalAudioExtension(fileName).trim()
    if (stem.isEmpty()) return FilenameParts(fileName, null)

    // Strip a leading track number: "01 - ", "01. ", "01 ".
    val withoutTrackNumber = stem.replace(Regex("""^\d{1,3}\s*[-._)]\s*"""), "").trim()

    val separator = Regex("""\s+[-–—]\s+""")
    val match = separator.find(withoutTrackNumber)
        ?: return FilenameParts(withoutTrackNumber.ifEmpty { stem }, null)

    val left = withoutTrackNumber.substring(0, match.range.first).trim()
    val right = withoutTrackNumber.substring(match.range.last + 1).trim()

    if (left.isEmpty() || right.isEmpty()) {
        return FilenameParts(withoutTrackNumber, null)
    }

    // "Artist - Title" is the dominant convention in the CJK music libraries this app targets;
    // the reverse ordering is handled by the tag path, which always wins when tags exist.
    return FilenameParts(title = right, artist = left)
}
