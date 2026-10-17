package app.rondo.core.decks.domain

import app.rondo.core.kernel.domain.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** Decks' own rules, who may do what with them, their levels and what studying one offers today. */
class DecksTest {
    private fun newId() = Uuid.generateV7().toString()

    private fun deck(
        owner: String?,
        parent: String? = null,
        id: String = newId(),
        gated: Boolean = false,
        unlockAt: Int = 100,
        position: String = "V",
    ) = Deck(id, "Deck", position, 0, 20, 200, 90, gated, unlockAt, 1, owner, parent)

    private val alice = newId()
    private val bob = newId()
    private val course = deck(alice)
    private val lesson = deck(alice, course.id)
    private val other = deck(alice)
    private val orphan = deck(null)
    private val tree = Tree(listOf(course, lesson, other, orphan))

    private fun access(who: String, lends: Map<String, Int> = emptyMap(), pro: Boolean = true) =
        Access(who, tree, lends) { pro }

    @Test
    fun roles() {
        assertEquals(Role.OWNER, access(alice).role(lesson.id))
        assertEquals(Role.EDITOR, access(bob, mapOf(course.id to 2)).role(lesson.id))
        assertEquals(Role.VIEWER, access(bob, mapOf(course.id to 2), pro = false).role(lesson.id))
        assertEquals(Role.VIEWER, access(bob, mapOf(course.id to 1)).role(lesson.id))
        assertEquals(Role.NONE, access(bob, mapOf(course.id to 2)).role(other.id))
        assertEquals(Role.READ_ONLY, access(alice).role(orphan.id))
    }

    @Test
    fun decks() {
        val editor = access(bob, mapOf(course.id to 2))
        val cases = listOf(
            Triple(access(alice), null to deck(alice, lesson.id), null),
            Triple(access(alice), null to deck(bob), ErrorCode.OWNER_MISMATCH),
            Triple(editor, null to deck(alice, lesson.id), null),
            Triple(editor, null to deck(bob, lesson.id), ErrorCode.OWNER_MISMATCH),
            Triple(editor, null to deck(alice, other.id), ErrorCode.FORBIDDEN),
            Triple(access(bob, mapOf(course.id to 1)), null to deck(alice, lesson.id), ErrorCode.FORBIDDEN),
            Triple(access(alice), course to course.copy(parentId = lesson.id), ErrorCode.CYCLE),
            Triple(access(alice), course to course.copy(ownerId = bob), ErrorCode.OWNER_MISMATCH),
            Triple(editor, course to course.copy(deletedAt = 1), ErrorCode.FORBIDDEN),
            Triple(editor, lesson to lesson.copy(parentId = null), ErrorCode.FORBIDDEN),
            Triple(editor, lesson to lesson.copy(name = "Renamed"), null),
            Triple(access(alice), orphan to orphan.copy(name = "x"), ErrorCode.READ_ONLY),
            Triple(access(alice), null to deck(alice).copy(name = " "), ErrorCode.INVALID),
            Triple(access(alice), null to deck(alice, unlockAt = 95), ErrorCode.INVALID),
        )
        for ((who, change, expected) in cases) {
            assertEquals(expected, change.second.check(who, change.first), change.second.toString())
        }
    }

    /** HSK 1 (500 notes), HSK 2 (800, needs 100% of HSK 1), HSK 3 (1,200, needs 90% of HSK 2). */
    private fun hsk(
        learned1: Int,
        learned2: Int = 0,
        unlocked: Set<String> = emptySet(),
    ): Triple<Map<String, Level>, List<String>, Set<String>> {
        val owner = newId()
        val hsk = deck(owner, gated = true, id = "hsk")
        val levels =
            listOf(
                deck(owner, hsk.id, "1", position = "1"),
                deck(owner, hsk.id, "2", position = "2"),
                deck(owner, hsk.id, "3", unlockAt = 90, position = "3"),
            )
        val notes = mapOf("1" to 500, "2" to 800, "3" to 1200)
        val learned = mapOf("1" to learned1, "2" to learned2)
        val opened = mutableSetOf<String>()
        val result = Tree(listOf(hsk) + levels).levels({
            DeckLoad(
                notes = notes[it] ?: 0,
                learnedNotes =
                learned[it] ?: 0,
            )
        }, unlocked, opened)
        return Triple(result, levels.map { it.id }, opened)
    }

    @Test
    fun levelsOpenInOrderAndRingsCountEverythingBefore() {
        val (start, ids, _) = hsk(learned1 = 1)
        assertEquals(listOf(true, false, false), ids.map { start.getValue(it).open })
        assertEquals(listOf(1, 1, 1), ids.map { start.getValue(it).ring })

        val (later, _, opened) = hsk(learned1 = 500)
        assertEquals(listOf(true, true, false), ids.map { later.getValue(it).open })
        assertEquals(41, later.getValue("3").ring)
        assertEquals(setOf("2"), opened)
        assertEquals(Level(true, 100, 1, 3), later.getValue("1"))

        val (almost, _, _) = hsk(learned1 = 500, learned2 = 719)
        assertEquals(99, almost.getValue("3").ring)
        assertEquals(false, almost.getValue("3").open)
        assertEquals(true, hsk(learned1 = 500, learned2 = 720).first.getValue("3").open)
    }

    @Test
    fun unlocksStickAndCanBeTakenManually() {
        val (skipped, _, opened) = hsk(learned1 = 0, unlocked = setOf("2"))
        assertEquals(true, skipped.getValue("2").open)
        assertEquals(false, skipped.getValue("3").open)
        assertEquals(emptySet(), opened)
        assertEquals(true, hsk(learned1 = 3, unlocked = setOf("2", "3")).first.getValue("3").open)
    }

    @Test
    fun countsSumTheSubtreeUnderTheOpenedDecksLimits() {
        val owner = newId()
        val parent = deck(owner, id = "p").copy(newPerDay = 20, reviewsPerDay = 5)
        val child = deck(owner, "p", "c")
        val tree = Tree(listOf(parent, child))
        val loads = mapOf(
            "p" to DeckLoad(newAvailable = 8, reviewDue = 4, newToday = 3),
            "c" to DeckLoad(newAvailable = 8, learningDue = 2, reviewDue = 4, reviewsToday = 1),
        )
        val open = mapOf("p" to Level(true, 0), "c" to Level(true, 0))
        assertEquals(Counts(16, 2, 4), tree.counts("p", { loads.getValue(it) }, open))
        val locked = open + ("c" to Level(false, 0))
        assertEquals(Counts(8, 2, 4), tree.counts("p", { loads.getValue(it) }, locked))
        assertEquals(Counts(8, 2, 4), tree.counts("c", { loads.getValue(it) }, open))
    }
}
