package top.izuna.foliamajor.local

import java.text.Normalizer

/**
 * Name normalisation and artist credit splitting, ported from folia-major's
 * `src/utils/localLibraryNames.ts`.
 *
 * These functions decide entity identity: two files credited to "周杰伦" and "周杰倫" must collapse to
 * one artist, and "A; B" must become two artists rather than one. The rules are small but every one
 * of them changes what the library looks like, so they are ported verbatim rather than approximated.
 */

/**
 * The identity key for an entity.
 *
 * NFKC folds full-width forms (`ＡＢＣ` → `ABC`, `；` → `;`), which matters because tags written by
 * different tools disagree on width. Whitespace is collapsed and lowercased so case never splits an
 * artist into two.
 */
fun normalizeLocalLibraryName(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .replace(Regex("""\s+"""), " ")
        .lowercase()

/** Display form: NFKC-normalised and whitespace-collapsed, but case preserved. */
fun cleanLocalLibraryName(value: String?): String? {
    val cleaned = value?.let { Normalizer.normalize(it, Normalizer.Form.NFKC) }
        ?.trim()
        ?.replace(Regex("""\s+"""), " ")
    return cleaned?.takeIf { it.isNotEmpty() }
}

/**
 * Splits an artist credit into distinct names.
 *
 * Only explicit separators count — semicolon (half and full width) and slash (half and full width).
 * Comma is deliberately absent: "Earth, Wind & Fire" is one artist, and splitting on comma is the
 * single most common way local libraries end up with phantom artists.
 *
 * Duplicates are dropped by normalised form, so "Beatles; the Beatles" yields one artist.
 */
fun splitLocalLibraryArtistNames(value: String?): List<String> {
    val cleaned = cleanLocalLibraryName(value) ?: return emptyList()

    val seen = mutableSetOf<String>()
    return cleaned
        .split(Regex("""[;；/／]"""))
        .mapNotNull { cleanLocalLibraryName(it) }
        .filter { part ->
            val normalized = normalizeLocalLibraryName(part)
            seen.add(normalized)
        }
}

/** Audio extensions the local library imports. Mirrors the desktop's KNOWN_AUDIO_EXTENSIONS. */
val KNOWN_AUDIO_EXTENSIONS = listOf(
    "mp3", "flac", "m4a", "wav", "ogg", "opus", "aac",
    // Desktop-only fallbacks: ffmpeg transcodes these, Android would need an equivalent path.
    "alac", "ape", "wv", "tta", "wma", "aif", "aiff", "caf",
)

/** Extensions Android can play directly, without a transcode step. */
val ANDROID_PLAYABLE_AUDIO_EXTENSIONS = listOf(
    "mp3", "flac", "m4a", "wav", "ogg", "opus", "aac",
)

/** Sidecar lyric extensions, in the order the desktop app ranks them by default. */
val LOCAL_LYRIC_FILE_FORMATS = listOf("lrc", "vtt", "ttml", "qrc", "yrc", "krc")

/** Folder cover file names, in priority order. */
val PREFERRED_FOLDER_COVER_FILES = listOf("cover.png", "cover.jpg", "cover.jpeg")

private val AUDIO_EXTENSION_REGEX = Regex(
    """\.(""" + KNOWN_AUDIO_EXTENSIONS.joinToString("|") + """)$""",
    RegexOption.IGNORE_CASE,
)

private val LYRIC_EXTENSION_REGEX = Regex("""\.(lrc|vtt|ttml|qrc|yrc|krc|fia)$""", RegexOption.IGNORE_CASE)
private val TRANSLATION_LYRIC_EXTENSION_REGEX = Regex("""\.t\.(lrc|vtt)$""", RegexOption.IGNORE_CASE)

fun isKnownAudioFile(fileName: String): Boolean = AUDIO_EXTENSION_REGEX.containsMatchIn(fileName)

fun isPlayableOnAndroid(fileName: String): Boolean {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return extension in ANDROID_PLAYABLE_AUDIO_EXTENSIONS
}

fun isTranslationLyricFile(fileName: String): Boolean =
    TRANSLATION_LYRIC_EXTENSION_REGEX.containsMatchIn(fileName)

/**
 * True for a main (non-translation) lyric file.
 *
 * Translation files are excluded explicitly: `track.t.lrc` also matches the lyric extension, and
 * treating it as a main lyric would make the translation win the format-priority contest and be
 * rendered as the sung text.
 */
fun isLyricFile(fileName: String): Boolean =
    LYRIC_EXTENSION_REGEX.containsMatchIn(fileName) && !isTranslationLyricFile(fileName)

/** Strips the audio extension, leaving the stem used for titles and sidecar matching. */
fun stripLocalAudioExtension(fileName: String): String =
    fileName.replace(AUDIO_EXTENSION_REGEX, "").trim()

/**
 * The key a sidecar lyric file is matched against.
 *
 * Both `track.lrc` and `track.mp3.lrc` are supported, so the lyric suffix is removed first and the
 * audio extension after — otherwise `track.mp3.lrc` would key as `track.mp3` and never match the
 * track's `track` key.
 */
fun sidecarLyricBasePath(relativePath: String, isTranslation: Boolean): String {
    val withoutLyricSuffix = if (isTranslation) {
        relativePath.replace(TRANSLATION_LYRIC_EXTENSION_REGEX, "")
    } else {
        relativePath.replace(LYRIC_EXTENSION_REGEX, "")
    }
    return withoutLyricSuffix.replace(AUDIO_EXTENSION_REGEX, "")
}

/** Lower is better. `.fia` (Folia's own export) always wins, then the user's format order. */
fun localLyricFilePriority(fileName: String, order: List<String> = LOCAL_LYRIC_FILE_FORMATS): Int {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    if (extension == "fia") return -1
    val index = order.indexOf(extension)
    return if (index == -1) Int.MAX_VALUE else index
}

/** Drops unknown/duplicate entries, then appends missing formats in default order. */
fun normalizeLocalLyricFormatOrder(value: List<String>?): List<String> {
    val order = value.orEmpty()
        .filter { it in LOCAL_LYRIC_FILE_FORMATS }
        .distinct()
        .toMutableList()

    LOCAL_LYRIC_FILE_FORMATS.forEach { format ->
        if (format !in order) order.add(format)
    }
    return order
}

/** The folder cover file to use for a directory, or null when it has none. */
fun preferredFolderCover(fileNames: List<String>): String? =
    fileNames
        .filter { name -> PREFERRED_FOLDER_COVER_FILES.any { it.equals(name, ignoreCase = true) } }
        .minByOrNull { name ->
            PREFERRED_FOLDER_COVER_FILES.indexOfFirst { it.equals(name, ignoreCase = true) }
        }

/** Path of a relative path's parent, or "" at the root. */
fun parentRelativePath(relativePath: String): String {
    val lastSlash = relativePath.lastIndexOf('/')
    return if (lastSlash == -1) "" else relativePath.substring(0, lastSlash)
}
