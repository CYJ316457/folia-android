package top.izuna.foliamajor.local

/**
 * Local library entity model, ported from folia-major's `src/types/localLibrary.ts`.
 *
 * The point of this layer is that an artist or album is an *identity*, not a string. A song stores a
 * reference to an entity id, so:
 *
 *  - Two spellings of one artist can be merged without touching a single song record.
 *  - An artist that was wrongly split (because one album wrote "A; B" and another wrote "A / B")
 *    can be rejoined later, and the merge is reversible by clearing the redirect.
 *  - An ambiguous match is flagged instead of guessed, so the library never silently welds two
 *    different artists together.
 *
 * `aliases` is what makes merging cheap: a merge only appends the loser's aliases to the winner and
 * points the loser at the winner. Every later lookup by either spelling resolves to the winner.
 */

enum class EntityKind { ARTIST, ALBUM }

/**
 * How a song's relation to an entity was established.
 *
 * Only `IMPORT` relations are recomputed on a rescan; everything else is a user decision and is
 * preserved. Without this distinction, every rescan would silently undo manual reorganising.
 */
enum class AssignmentOrigin { IMPORT, AUTO_MATCH, MANUAL_MATCH, MANUAL, SPLIT }

data class LibraryEntity(
    val id: String,
    val kind: EntityKind,
    val displayName: String,
    val aliases: List<String>,
    val normalizedAliases: List<String>,
    /** When set, this entity has been merged into another and must be resolved through it. */
    val mergedInto: String? = null,
    /**
     * True when the import found more than one existing entity matching this name.
     *
     * The resolver creates a *new* entity in that case rather than picking one, because picking is
     * unrecoverable: a wrong merge silently attributes one artist's songs to another, and there is
     * no signal afterwards that it happened. A flagged entity shows up in the UI as needing review.
     */
    val needsReview: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** One song's membership in the artist/album graph. */
data class LibraryAssignment(
    val songId: String,
    val artistEntityIds: List<String>,
    val artistOrigin: AssignmentOrigin,
    val albumEntityId: String?,
    val albumOrigin: AssignmentOrigin,
    val updatedAt: Long = 0L,
)

/** The whole graph, loaded together so consumers never see mismatched revisions. */
data class LibraryCatalog(
    val entities: List<LibraryEntity> = emptyList(),
    val assignments: List<LibraryAssignment> = emptyList(),
)

/**
 * Precomputed lookup tables.
 *
 * Rebuilt whenever the entity list changes rather than maintained incrementally: the lists are a few
 * thousand entries and an import rewrites them wholesale, so the index is cheap and always correct.
 */
class LibraryIndex(entities: List<LibraryEntity>) {

    val entitiesById: Map<String, LibraryEntity> = entities.associateBy { it.id }

    /** Keyed by `"<kind>:<normalised alias>"`, because an artist and an album may share a name. */
    val entityIdsByAlias: Map<String, List<String>> = buildMap {
        for (entity in entities) {
            for (alias in entity.normalizedAliases) {
                val key = aliasKey(entity.kind, alias)
                put(key, get(key).orEmpty() + entity.id)
            }
        }
    }

    /**
     * Follows `mergedInto` to the surviving entity.
     *
     * The `visited` set is not defensive noise: a bad merge (A→B, B→A) would otherwise hang the
     * resolver, and merge operations are user-driven and therefore capable of producing a cycle.
     */
    fun followRedirect(entityId: String): String? {
        var current = entitiesById[entityId] ?: return null
        val visited = mutableSetOf<String>()

        while (current.mergedInto != null && visited.add(current.id)) {
            current = entitiesById[current.mergedInto!!] ?: return current.id
        }
        return current.id
    }

    /** Entities that are still directly addressable (not merged away). */
    fun activeEntities(kind: EntityKind? = null): List<LibraryEntity> =
        entitiesById.values.filter { it.mergedInto == null && (kind == null || it.kind == kind) }
}

internal fun aliasKey(kind: EntityKind, normalizedAlias: String): String =
    "${kind.name.lowercase()}:$normalizedAlias"

/**
 * The id of a newly created entity.
 *
 * Deliberately derived from a counter plus a random suffix rather than `UUID.randomUUID()`, so unit
 * tests can predict ids while real runs stay collision-free.
 */
object EntityIdGenerator {
    private var counter = 0L

    fun next(): String {
        counter += 1
        return "entity-$counter-${(0..0xFFFFFF).random().toString(16).padStart(6, '0')}"
    }

    /** Test hook: resets the counter so assertions on ids stay stable. */
    fun resetForTest() {
        counter = 0
    }
}
