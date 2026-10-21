package top.izuna.foliamajor.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for entity resolution and merging.
 *
 * The rule under test is "merge only when certain, otherwise flag". Every test here exists because
 * the alternative — a resolver that always picks a best match — produces a library that looks
 * correct while quietly attributing one artist's songs to another.
 */
class LibraryEntityResolverTest {

    @Before
    fun resetIds() {
        EntityIdGenerator.resetForTest()
    }

    private fun artist(id: String, name: String, aliases: List<String> = listOf(name)) = LibraryEntity(
        id = id,
        kind = EntityKind.ARTIST,
        displayName = name,
        aliases = aliases,
        normalizedAliases = aliases.map { normalizeLocalLibraryName(it) },
    )

    private fun album(id: String, name: String) = LibraryEntity(
        id = id,
        kind = EntityKind.ALBUM,
        displayName = name,
        aliases = listOf(name),
        normalizedAliases = listOf(normalizeLocalLibraryName(name)),
    )

    // --- Single match ----------------------------------------------------------------------------

    @Test
    fun `an exact alias match reuses the entity`() {
        val entities = mutableListOf(artist("a1", "周杰伦"))

        val resolved = resolveLibraryEntity(entities, EntityKind.ARTIST, "周杰伦")

        assertEquals("a1", resolved?.entity?.id)
        assertFalse(resolved!!.created)
        assertEquals(1, entities.size)
    }

    @Test
    fun `a case-different name matches`() {
        val entities = mutableListOf(artist("a1", "The Beatles"))

        val resolved = resolveLibraryEntity(entities, EntityKind.ARTIST, "the beatles")

        assertEquals("a1", resolved?.entity?.id)
    }

    @Test
    fun `a full-width name matches`() {
        val entities = mutableListOf(artist("a1", "ABC"))

        val resolved = resolveLibraryEntity(entities, EntityKind.ARTIST, "ＡＢＣ")

        assertEquals("a1", resolved?.entity?.id)
    }

    @Test
    fun `an extra alias matches`() {
        // A merged entity carries the loser's names as aliases; both spellings resolve to it.
        val entities = mutableListOf(artist("a1", "周杰伦", aliases = listOf("周杰伦", "周杰倫")))

        assertEquals("a1", resolveLibraryEntity(entities, EntityKind.ARTIST, "周杰倫")?.entity?.id)
    }

    @Test
    fun `a blank name resolves to nothing`() {
        val entities = mutableListOf(artist("a1", "A"))

        assertNull(resolveLibraryEntity(entities, EntityKind.ARTIST, "   "))
    }

    // --- Creation --------------------------------------------------------------------------------

    @Test
    fun `an unknown name creates an entity`() {
        val entities = mutableListOf<LibraryEntity>()

        val resolved = resolveLibraryEntity(entities, EntityKind.ARTIST, "New Artist")

        assertTrue(resolved!!.created)
        assertEquals("New Artist", resolved.entity.displayName)
        assertEquals(1, entities.size)
        assertFalse(resolved.entity.needsReview)
    }

    @Test
    fun `a new entity records its display name as an alias`() {
        val entities = mutableListOf<LibraryEntity>()
        val resolved = resolveLibraryEntity(entities, EntityKind.ARTIST, "  New   Artist  ")

        assertEquals("New Artist", resolved!!.entity.displayName)
        assertEquals(listOf("New Artist"), resolved.entity.aliases)
        assertEquals(listOf("new artist"), resolved.entity.normalizedAliases)
    }

    @Test
    fun `artist and album namespaces are separate`() {
        // An album called "Greatest Hits" must not satisfy an artist lookup of the same name.
        val entities = mutableListOf(album("al1", "Greatest Hits"))

        val resolved = resolveLibraryEntity(entities, EntityKind.ARTIST, "Greatest Hits")

        assertTrue(resolved!!.created)
        assertNotEquals("al1", resolved.entity.id)
    }

    // --- Ambiguity -------------------------------------------------------------------------------

    @Test
    fun `two entities sharing a name create a flagged entity instead of guessing`() {
        // This is the whole point of the design: no context, so do not pick.
        val entities = mutableListOf(
            artist("a1", "Same Name", aliases = listOf("Same Name", "SN")),
            artist("a2", "Same Name"),
        )

        val resolved = resolveLibraryEntity(entities, EntityKind.ARTIST, "Same Name")

        assertTrue(resolved!!.created)
        assertTrue("must be flagged for review", resolved.entity.needsReview)
        assertEquals(3, entities.size)
    }

    @Test
    fun `context disambiguates an otherwise ambiguous name`() {
        val entities = mutableListOf(
            artist("a1", "Same Name"),
            artist("a2", "Same Name"),
        )

        // The song already pointed at a2, so that is the answer.
        val resolved = resolveLibraryEntity(
            entities = entities,
            kind = EntityKind.ARTIST,
            name = "Same Name",
            currentEntityId = "a2",
        )

        assertEquals("a2", resolved?.entity?.id)
        assertFalse(resolved!!.created)
    }

    @Test
    fun `preferred context disambiguates when the current one does not`() {
        val entities = mutableListOf(
            artist("a1", "Same Name"),
            artist("a2", "Same Name"),
        )

        val resolved = resolveLibraryEntity(
            entities = entities,
            kind = EntityKind.ARTIST,
            name = "Same Name",
            preferredEntityIds = listOf("a1"),
        )

        assertEquals("a1", resolved?.entity?.id)
    }

    @Test
    fun `a current id that is not a candidate is ignored`() {
        // Context may break a tie but must never invent a match.
        val entities = mutableListOf(artist("a1", "One"), artist("a2", "Two"))

        val resolved = resolveLibraryEntity(
            entities = entities,
            kind = EntityKind.ARTIST,
            name = "One",
            currentEntityId = "a2",
        )

        assertEquals("a1", resolved?.entity?.id)
    }

    @Test
    fun `resolving through a merged entity counts as one candidate`() {
        // a2 was merged into a1, so both alias entries point at a1: unambiguous, not ambiguous.
        val entities = mutableListOf(
            artist("a1", "Canonical", aliases = listOf("Canonical", "Alias")),
            artist("a2", "Alias").copy(mergedInto = "a1"),
        )

        val resolved = resolveLibraryEntity(entities, EntityKind.ARTIST, "Alias")

        assertEquals("a1", resolved?.entity?.id)
        assertFalse(resolved!!.created)
    }

    // --- Several names ---------------------------------------------------------------------------

    @Test
    fun `several names resolve in order`() {
        val entities = mutableListOf(artist("a1", "A"))

        val ids = resolveLibraryEntityNames(entities, EntityKind.ARTIST, listOf("A", "B"))

        assertEquals(2, ids.size)
        assertEquals("a1", ids[0])
        assertEquals(2, entities.size)
    }

    @Test
    fun `duplicate names collapse to one id`() {
        val entities = mutableListOf<LibraryEntity>()

        val ids = resolveLibraryEntityNames(entities, EntityKind.ARTIST, listOf("A", "A"))

        assertEquals(listOf(ids[0]), ids)
        assertEquals(1, entities.size)
    }

    @Test
    fun `blank names are skipped`() {
        val entities = mutableListOf<LibraryEntity>()

        val ids = resolveLibraryEntityNames(entities, EntityKind.ARTIST, listOf("A", "", "  "))

        assertEquals(1, ids.size)
    }

    // --- Merging ---------------------------------------------------------------------------------

    @Test
    fun `merging moves the aliases to the target`() {
        val entities = mutableListOf(artist("a1", "Canonical"), artist("a2", "Variant"))

        assertTrue(mergeLibraryEntities(entities, "a2", "a1"))

        val target = entities.first { it.id == "a1" }
        assertTrue(target.aliases.contains("Variant"))
        assertTrue(target.normalizedAliases.contains("variant"))
    }

    @Test
    fun `merging redirects the source`() {
        val entities = mutableListOf(artist("a1", "Canonical"), artist("a2", "Variant"))
        mergeLibraryEntities(entities, "a2", "a1")

        assertEquals("a1", entities.first { it.id == "a2" }.mergedInto)
    }

    @Test
    fun `a merged name resolves to the survivor`() {
        val entities = mutableListOf(artist("a1", "Canonical"), artist("a2", "Variant"))
        mergeLibraryEntities(entities, "a2", "a1")

        assertEquals("a1", resolveLibraryEntity(entities, EntityKind.ARTIST, "Variant")?.entity?.id)
    }

    @Test
    fun `merging clears needsReview on both sides`() {
        val entities = mutableListOf(
            artist("a1", "A").copy(needsReview = true),
            artist("a2", "B").copy(needsReview = true),
        )

        mergeLibraryEntities(entities, "a2", "a1")

        assertFalse(entities.first { it.id == "a1" }.needsReview)
        assertFalse(entities.first { it.id == "a2" }.needsReview)
    }

    @Test
    fun `merging across kinds is refused`() {
        val entities = mutableListOf(artist("a1", "A"), album("al1", "A"))

        assertFalse(mergeLibraryEntities(entities, "al1", "a1"))
    }

    @Test
    fun `merging an entity into itself is refused`() {
        val entities = mutableListOf(artist("a1", "A"))

        assertFalse(mergeLibraryEntities(entities, "a1", "a1"))
    }

    @Test
    fun `merging a missing entity is refused`() {
        val entities = mutableListOf(artist("a1", "A"))

        assertFalse(mergeLibraryEntities(entities, "nope", "a1"))
    }

    @Test
    fun `merging an already-merged entity is refused`() {
        val entities = mutableListOf(artist("a1", "A"), artist("a2", "B"), artist("a3", "C"))
        mergeLibraryEntities(entities, "a2", "a1")

        assertFalse(mergeLibraryEntities(entities, "a2", "a3"))
    }

    @Test
    fun `a merge cycle is refused`() {
        // A→B then B→A would make followRedirect loop; the second merge must be rejected.
        val entities = mutableListOf(artist("a1", "A"), artist("a2", "B"))
        mergeLibraryEntities(entities, "a1", "a2")

        assertFalse(mergeLibraryEntities(entities, "a2", "a1"))
    }

    @Test
    fun `a redirect chain resolves to its end`() {
        val entities = mutableListOf(artist("a1", "A"), artist("a2", "B"), artist("a3", "C"))
        mergeLibraryEntities(entities, "a2", "a1")
        mergeLibraryEntities(entities, "a3", "a1")

        assertEquals("a1", resolveLibraryEntity(entities, EntityKind.ARTIST, "C")?.entity?.id)
    }

    // --- Unmerging -------------------------------------------------------------------------------

    @Test
    fun `unmerging clears the redirect`() {
        val entities = mutableListOf(artist("a1", "Canonical"), artist("a2", "Variant"))
        mergeLibraryEntities(entities, "a2", "a1")

        assertTrue(unmergeLibraryEntity(entities, "a2"))

        assertNull(entities.first { it.id == "a2" }.mergedInto)
    }

    @Test
    fun `an unmerged entity resolves by its own name again`() {
        val entities = mutableListOf(artist("a1", "Canonical"), artist("a2", "Variant"))
        mergeLibraryEntities(entities, "a2", "a1")
        unmergeLibraryEntity(entities, "a2")

        assertEquals("a2", resolveLibraryEntity(entities, EntityKind.ARTIST, "Variant")?.entity?.id)
    }

    @Test
    fun `unmerging a non-merged entity is refused`() {
        val entities = mutableListOf(artist("a1", "A"))

        assertFalse(unmergeLibraryEntity(entities, "a1"))
    }

    // --- Index helpers ---------------------------------------------------------------------------

    @Test
    fun `active entities exclude merged ones`() {
        val entities = listOf(
            artist("a1", "A"),
            artist("a2", "B").copy(mergedInto = "a1"),
        )

        val active = LibraryIndex(entities).activeEntities(EntityKind.ARTIST)

        assertEquals(listOf("a1"), active.map { it.id })
    }

    @Test
    fun `entities resolving to a target include the merged sources`() {
        val entities = listOf(
            artist("a1", "A"),
            artist("a2", "B").copy(mergedInto = "a1"),
            artist("a3", "C"),
        )

        val resolving = entitiesResolvingTo(entities, "a1")

        assertEquals(setOf("a1", "a2"), resolving.map { it.id }.toSet())
    }
}
