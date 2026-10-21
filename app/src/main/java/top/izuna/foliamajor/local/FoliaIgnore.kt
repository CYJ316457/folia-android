package top.izuna.foliamajor.local

/**
 * `.foliaignore` parsing, ported from folia-major's `src/utils/foliaIgnore.ts`.
 *
 * A gitignore-style exclusion file, read from the import root and from every subdirectory. The
 * syntax is a deliberate subset of gitignore: `*`, `?`, `**`, character classes, `!` negation,
 * trailing `/` for directories, and leading `/` to anchor to the rule's own directory.
 *
 * The semantics that matter and are easy to get wrong:
 *
 *  - **Later rules win.** A file is not "matched by any rule"; it is matched by the *last* rule that
 *    applies, which is what makes `*.mp3` followed by `!keep.mp3` work.
 *  - **A rule's directory is its base.** A `.foliaignore` inside `Album/` sees paths relative to
 *    `Album/`, so `/private.mp3` there means `Album/private.mp3`, not the root's.
 *  - **A directory rule also covers everything under it.** Ignoring `cache/` must stop the walk, not
 *    merely hide one entry.
 */

/** One parsed line. */
private data class IgnoreRule(
    val negated: Boolean,
    val directoryOnly: Boolean,
    val matcher: Regex,
)

/**
 * A compiled matcher for one `.foliaignore` file.
 *
 * [baseDirectory] is the directory the file lives in, relative to the import root ("" for the root
 * itself). Paths are rebased onto it before matching.
 */
class FoliaIgnoreMatcher private constructor(
    private val rules: List<IgnoreRule>,
    private val baseDirectory: String,
) {
    val ruleCount: Int get() = rules.size

    /**
     * Whether a path is ignored, or null when no rule mentions it.
     *
     * Null is distinct from false: a later `.foliaignore` in a deeper directory only overrides paths
     * its own rules actually mention, so the distinction decides whether an outer rule still applies.
     */
    fun decisionFor(relativePath: String, isDirectory: Boolean): Boolean? {
        val localPath = rebase(relativePath) ?: return null
        if (localPath.isEmpty()) return null

        var decision: Boolean? = null
        for (rule in rules) {
            if (rule.directoryOnly && !isDirectory) continue
            if (rule.matcher.containsMatchIn(localPath)) {
                decision = !rule.negated
            }
        }
        return decision
    }

    fun isIgnored(relativePath: String, isDirectory: Boolean): Boolean =
        decisionFor(relativePath, isDirectory) == true

    /**
     * Rebases a root-relative path onto this matcher's directory.
     *
     * Returns null when the path is outside the directory, which means this matcher has no opinion.
     */
    private fun rebase(relativePath: String): String? {
        val normalized = normalizeIgnorePath(relativePath)
        if (baseDirectory.isEmpty()) return normalized
        return when {
            normalized == baseDirectory -> ""
            normalized.startsWith("$baseDirectory/") -> normalized.substring(baseDirectory.length + 1)
            else -> null
        }
    }

    companion object {
        /** An empty matcher, for directories with no `.foliaignore`. */
        val EMPTY = FoliaIgnoreMatcher(emptyList(), "")

        fun parse(contents: String, baseDirectory: String = ""): FoliaIgnoreMatcher {
            val rules = contents.removePrefix("\uFEFF")
                .split('\n')
                .mapNotNull { parseRule(it) }

            return FoliaIgnoreMatcher(rules, normalizeIgnorePath(baseDirectory))
        }
    }
}

private fun normalizeIgnorePath(value: String): String =
    value.replace('\\', '/')
        .removePrefix("./")
        .trim('/')

private fun escapeRegexCharacter(character: Char): String =
    if (character in "\\^$.*+?()[]{}|") "\\$character" else character.toString()

/**
 * Compiles the glob subset.
 *
 * A double star is the only multi-segment wildcard: it matches zero or more directories when
 * followed by a slash, and anything at all otherwise. Single-star and question-mark never cross a
 * slash, which is why a basename pattern needs the extra anchoring branch in [parseRule].
 */
private fun compileGlobBody(glob: String): String {
    val result = StringBuilder()
    var index = 0

    while (index < glob.length) {
        when (val character = glob[index]) {
            '*' -> {
                if (index + 1 < glob.length && glob[index + 1] == '*') {
                    index += 1
                    if (index + 1 < glob.length && glob[index + 1] == '/') {
                        index += 1
                        result.append("(?:.*/)?")
                    } else {
                        result.append(".*")
                    }
                } else {
                    result.append("[^/]*")
                }
            }

            '?' -> result.append("[^/]")

            '[' -> {
                val closing = glob.indexOf(']', index + 1)
                if (closing > index + 1) {
                    val rawClass = glob.substring(index + 1, closing)
                    val normalizedClass = if (rawClass.startsWith("!")) {
                        "^" + rawClass.substring(1)
                    } else {
                        rawClass
                    }
                    result.append("[").append(normalizedClass.replace("\\", "\\\\")).append("]")
                    index = closing
                } else {
                    result.append("\\[")
                }
            }

            else -> result.append(escapeRegexCharacter(character))
        }
        index += 1
    }

    return result.toString()
}

private fun parseRule(sourceLine: String): IgnoreRule? {
    var line = sourceLine.removeSuffix("\r").trim()
    if (line.isEmpty() || line.startsWith("#")) return null

    // A leading backslash escapes a literal `#` or `!`.
    val escapedLeadingMarker = line.startsWith("\\#") || line.startsWith("\\!")
    if (escapedLeadingMarker) line = line.substring(1)

    val negated = !escapedLeadingMarker && line.startsWith("!")
    if (negated) line = line.substring(1)
    if (line.isEmpty()) return null

    val directoryOnly = line.endsWith("/")
    if (directoryOnly) line = line.dropLast(1)

    val anchored = line.startsWith("/")
    if (anchored) line = line.substring(1)

    val normalizedPattern = normalizeIgnorePath(line)
    if (normalizedPattern.isEmpty()) return null

    val patternHasSlash = normalizedPattern.contains('/')
    val body = compileGlobBody(normalizedPattern)

    // A pattern with no slash matches a basename at any depth, which is why `*.tmp.mp3` catches
    // files in subdirectories. A pattern with a slash is anchored to the rule's directory.
    val matcher = if (anchored || patternHasSlash) {
        Regex("^$body$")
    } else {
        Regex("(?:^|/)$body$")
    }

    return IgnoreRule(negated = negated, directoryOnly = directoryOnly, matcher = matcher)
}

/**
 * Applies a stack of matchers, outermost first.
 *
 * Each matcher only overrides paths its own rules mention, so a nested `.foliaignore` can re-include
 * something the root excluded — but only within its own directory.
 */
fun isIgnoredByFoliaMatchers(
    matchers: List<FoliaIgnoreMatcher>,
    relativePath: String,
    isDirectory: Boolean,
): Boolean {
    var ignored = false
    for (matcher in matchers) {
        when (matcher.decisionFor(relativePath, isDirectory)) {
            true -> ignored = true
            false -> ignored = false
            null -> Unit
        }
    }
    return ignored
}
