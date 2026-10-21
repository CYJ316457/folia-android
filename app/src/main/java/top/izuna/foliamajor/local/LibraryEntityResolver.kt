package top.izuna.foliamajor.local

/**
 * Resolves names to stable entities, ported from folia-major's `src/utils/localLibraryResolver.ts`.
 *
 * The governing rule is: **merge only when certain, otherwise flag**. A resolver that always picks
 * "the best match" will eventually weld two different artists with the same name into one, and that
 * is unrecoverable — the songs keep playing, nothing looks broken, and there is no signal that the
 * library is wrong. So:
 *
 *  - Exactly one entity matches the alias → use it.
 *  - Several match, but one of them is the song's current entity or came from the surrounding
 *    context (the same album's other artists) → use that one.
 *  - Several match and nothing disambiguates them → create a NEW entity flagged `needsReview`.
 *
 * A flagged entity is visible in the UI and can be merged by hand later. Guessing is not.
 */

/** The outcome of resolving one name. */
data class ResolvedEntity(
    val entity: LibraryEntity,
    /** True when a new entity was created and must be appended to the caller's list. */
    val created: Boolean,
)

/**
 * Resolves one name.
 *
 * `currentEntityId` is the song's existing artist entity for this slot, if any; `preferredEntityIds`
 * is the surrounding context (for artists, the other artists already credited on the same album).
 * Both are tried only when they are *among* the alias matches, so context can break a tie but never
 * invent a match.
 *
 * Returns null for a blank name, which is how a track with no artist tag ends up with no artist.
 */
fun resolveLibraryEntity(
    entities: MutableList<LibraryEntity>,
    kind: EntityKind,
    name: String,
    currentEntityId: String? = null,
    preferredEntityIds: List<String> = emptyList(),
    now: Long = System.currentTimeMillis(),
    createId: () -> String = { EntityIdGenerator.next() },
): ResolvedEntity? {
    val displayName = cleanLocalLibraryName(name) ?: return null
    val normalizedName = normalizeLocalLibraryName(displayName)

    val index = LibraryIndex(entities)

    val candidateIds = (index.entityIdsByAlias[aliasKey(kind, normalizedName)] ?: emptyList())
        .mapNotNull { index.followRedirect(it) }
        .distinct()

    val contextualIds = (listOfNotNull(currentEntityId) + preferredEntityIds)
        .mapNotNull { index.followRedirect(it) }

    val contextualMatch = contextualIds.firstOrNull { it in candidateIds }

    // A single candidate is unambiguous. Several candidates are only acceptable when the context
    // picks one of them; otherwise the caller gets a new, flagged entity.
    val resolvedId = when {
        candidateIds.size == 1 -> candidateIds.first()
        candidateIds.size > 1 -> contextualMatch
        else -> null
    }

    index.entitiesById[resolvedId]?.let { existing ->
        return ResolvedEntity(existing, created = false)
    }

    val entity = LibraryEntity(
        id = createId(),
        kind = kind,
        displayName = displayName,
        aliases = listOf(displayName),
        normalizedAliases = listOf(normalizedName),
        // More than one existing entity claims this name: the import cannot tell which one this song
        // belongs to, so it records the ambiguity instead of picking.
        needsReview = candidateIds.size > 1,
        createdAt = now,
        updatedAt = now,
    )
    entities.add(entity)
    return ResolvedEntity(entity, created = true)
}

/**
 * Resolves several names in order, creating entities as needed.
 *
 * Duplicate ids are collapsed: a track credited "A; A" is one artist, not two links to the same one.
 */
fun resolveLibraryEntityNames(
    entities: MutableList<LibraryEntity>,
    kind: EntityKind,
    names: List<String>,
    preferredEntityIds: List<String> = emptyList(),
    now: Long = System.currentTimeMillis(),
    createId: () -> String = { EntityIdGenerator.next() },
): List<String> {
    val ids = mutableListOf<String>()

    for (name in names) {
        val resolved = resolveLibraryEntity(
            entities = entities,
            kind = kind,
            name = name,
            preferredEntityIds = preferredEntityIds,
            now = now,
            createId = createId,
        ) ?: continue
        ids.add(resolved.entity.id)
    }

    return ids.distinct()
}

/**
 * Merges one entity into another.
 *
 * The loser is not deleted: its aliases move to the winner, and it is marked `mergedInto` so every
 * reference to it (including references held by song assignments) keeps resolving. That is what
 * makes a merge reversible — clearing `mergedInto` undoes it without touching a single song.
 *
 * Refuses to merge across kinds, and refuses a merge that would create a redirect cycle.
 */
fun mergeLibraryEntities(
    entities: MutableList<LibraryEntity>,
    sourceId: String,
    targetId: String,
    now: Long = System.currentTimeMillis(),
): Boolean {
    if (sourceId == targetId) return false

    val index = LibraryIndex(entities)
    val sourceIndex = entities.indexOfFirst { it.id == sourceId }
    val targetIndex = entities.indexOfFirst { it.id == targetId }
    if (sourceIndex < 0 || targetIndex < 0) return false

    val source = entities[sourceIndex]
    val target = entities[targetIndex]

    if (source.kind != target.kind) return false
    if (source.mergedInto != null || target.mergedInto != null) return false

    // Following the target's own redirect chain must never reach the source, or the pair becomes a
    // cycle that the resolver's visited-set would have to break by guessing.
    if (index.followRedirect(targetId) == sourceId) return false

    val mergedAliases = (target.aliases + source.aliases).distinct()
    val mergedNormalized = (target.normalizedAliases + source.normalizedAliases).distinct()

    entities[targetIndex] = target.copy(
        aliases = mergedAliases,
        normalizedAliases = mergedNormalized,
        // The merged entity is no longer ambiguous: the user resolved it.
        needsReview = false,
        updatedAt = now,
    )

    entities[sourceIndex] = source.copy(
        mergedInto = targetId,
        needsReview = false,
        updatedAt = now,
    )

    return true
}

/**
 * Undoes a merge.
 *
 * The loser's aliases are reclaimed from the winner where they can be identified: any alias equal to
 * the loser's display name is removed from the winner and given back. Aliases that came from *other*
 * sources stay on the winner.
 *
 * If the winner still claimed the name, an unmerge would leave two entities answering to it — which
 * the resolver reads as ambiguity and flags for review, so the unmerge would look like it failed.
 * Removing the name from the winner is what makes the operation actually take effect.
 */
fun unmergeLibraryEntity(
    entities: MutableList<LibraryEntity>,
    entityId: String,
    now: Long = System.currentTimeMillis(),
): Boolean {
    val index = entities.indexOfFirst { it.id == entityId }
    if (index < 0) return false

    val entity = entities[index]
    val targetId = entity.mergedInto ?: return false

    val normalizedName = normalizeLocalLibraryName(entity.displayName)

    // Reclaim the name from the survivor so exactly one entity answers to it afterwards.
    val targetIndex = entities.indexOfFirst { it.id == targetId }
    if (targetIndex >= 0) {
        val target = entities[targetIndex]
        entities[targetIndex] = target.copy(
            aliases = target.aliases.filterNot { normalizeLocalLibraryName(it) == normalizedName },
            normalizedAliases = target.normalizedAliases.filterNot { it == normalizedName },
            updatedAt = now,
        )
    }

    entities[index] = entity.copy(
        mergedInto = null,
        aliases = listOf(entity.displayName),
        normalizedAliases = listOf(normalizedName),
        updatedAt = now,
    )
    return true
}

/** Every entity that resolves to the given one, including itself. Used by the artist/album views. */
fun entitiesResolvingTo(entities: List<LibraryEntity>, targetId: String): List<LibraryEntity> {
    val index = LibraryIndex(entities)
    return entities.filter { index.followRedirect(it.id) == targetId }
}
