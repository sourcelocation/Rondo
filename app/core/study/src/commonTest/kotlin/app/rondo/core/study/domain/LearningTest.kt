package app.rondo.core.study.domain

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
}
