package app.rondo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearningTest {
    private val me = newId()

    private fun review(at: Long, rating: Int, due: Long? = null) =
        Event.Review(newId(), me, "n", 1, at, rating, due = due)

    private fun state(vararg events: Event) = Card("n", 1, events.toList()).state(Fsrs(fuzz = false))

    @Test
    fun aCardFoldsOfflineDevicesAndHonoursVoidsAndSnapshots() {
        val good = review(0, 3)
        val again = review(60_000, 1)
        val graduate = review(120_000, 3)
        val merged = state(graduate, good, again)
        assertEquals(state(good, again, graduate), merged)
        assertEquals(3, merged.memory.reps)
        assertEquals(0L, merged.introducedAt)

        val undone = state(good, again, graduate, Event.Void(newId(), me, graduate.id, 130_000))
        assertEquals(2, undone.memory.reps)

        val stored = state(review(0, 4, due = 42))
        assertEquals(42L, stored.memory.due)
        assertEquals(0L, stored.learnedAt)

        val snapshot = Event.Snapshot(newId(), me, "n", 1, Memory(Memory.REVIEW, 0, 12.0, 4.0, 9_000, 500, 7, 1))
        val copied = state(
            snapshot,
            Event.Flag(newId(), me, "n", 1, 600, 3),
            Event.Suspend(newId(), me, "n", 1, 700),
        )
        assertEquals(Memory(Memory.REVIEW, 0, 12.0, 4.0, 9_000, 500, 7, 1), copied.memory)
        assertEquals(500L, copied.learnedAt)
        assertEquals(3, copied.flag)
        assertTrue(copied.suspended)
        assertEquals(CardState(), state(good, Event.Reset(newId(), me, "n", 1, 10)).copy(buriedAt = null))
    }

    @Test
    fun aSnapshotKeepsWhereACardStandsWithoutItsReviews() {
        val learned =
            CardState(Memory(Memory.REVIEW, stability = 12.0, difficulty = 5.0, due = 100, last = 50, reps = 3))
        val snapshot = learned.snapshot("n", 2, "you", 1_000)!!
        assertEquals("n", snapshot.subjectId)
        assertEquals(2, snapshot.card)
        assertEquals("you", snapshot.userId)
        assertEquals(50L, snapshot.at)
        assertEquals(learned.memory, Card("n", 2, listOf(snapshot)).state(Fsrs()).memory)
        assertNull(CardState().snapshot("n", 1, "you", 1_000), "a new card needs none")
    }

    /** HSK 1 (500 notes), HSK 2 (800, needs 100% of HSK 1), HSK 3 (1,200, needs 90% of HSK 2). */
    private fun course(
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
        val (start, ids, _) = course(learned1 = 1)
        assertEquals(listOf(true, false, false), ids.map { start.getValue(it).open })
        assertEquals(listOf(1, 1, 1), ids.map { start.getValue(it).ring })

        val (later, _, opened) = course(learned1 = 500)
        assertEquals(listOf(true, true, false), ids.map { later.getValue(it).open })
        assertEquals(41, later.getValue("3").ring)
        assertEquals(setOf("2"), opened)
        assertEquals(Level(true, 100, 1, 3), later.getValue("1"))

        val (almost, _, _) = course(learned1 = 500, learned2 = 719)
        assertEquals(99, almost.getValue("3").ring)
        assertEquals(false, almost.getValue("3").open)
        assertEquals(true, course(learned1 = 500, learned2 = 720).first.getValue("3").open)
    }

    @Test
    fun unlocksStickAndCanBeTakenManually() {
        val (skipped, _, opened) = course(learned1 = 0, unlocked = setOf("2"))
        assertEquals(true, skipped.getValue("2").open)
        assertEquals(false, skipped.getValue("3").open)
        assertEquals(emptySet(), opened)
        assertEquals(true, course(learned1 = 3, unlocked = setOf("2", "3")).first.getValue("3").open)
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
