package app.rondo.core.study.domain

import app.rondo.core.decks.domain.Access
import app.rondo.core.decks.domain.Deck
import app.rondo.core.decks.domain.Tree
import app.rondo.core.kernel.domain.ErrorCode
import app.rondo.core.notes.domain.Filter
import kotlin.test.Test
import kotlin.test.assertEquals

/** Smart decks', events' and settings' own rules: the learner's alone. */
class StudyRulesTest {
    private val alice = newId()
    private val bob = newId()
    private val course = Deck("Course", "V", 1, alice, null)
    private val tree = Tree(listOf(course))

    private fun access(who: String, lends: Map<String, Int> = emptyMap()) = Access(who, tree, lends) { true }

    @Test
    fun smartDecks() {
        val filter = Filter(deckIds = listOf(course.id), tags = listOf("Cardio"), state = "review")
        val mine = SmartDeck("Cardio", filter, "V", 1, alice)
        assertEquals(null, mine.check(access(alice), null))
        assertEquals(ErrorCode.FORBIDDEN, mine.check(access(bob, mapOf(course.id to 2)), null))
        assertEquals(ErrorCode.OWNER_MISMATCH, mine.copy(ownerId = bob).check(access(bob), mine))
        assertEquals(ErrorCode.INVALID, mine.copy(name = " ").check(access(alice), null))
        assertEquals(ErrorCode.INVALID, mine.copy(newPerDay = -1).check(access(alice), null))
        val strange = listOf(
            filter.copy(state = "lost"),
            filter.copy(tags = listOf("two words")),
            filter.copy(deckIds = listOf("x")),
        )
        for (f in strange) assertEquals(ErrorCode.INVALID, mine.copy(filter = f).check(access(alice), null))
    }

    @Test
    fun eventsAndSettings() {
        val review = Event.Review(newId(), alice, newId(), 1, 1_000, rating = 3)
        assertEquals(null, review.check(access(alice), 1_000))
        assertEquals(ErrorCode.FORBIDDEN, review.check(access(bob), 1_000))
        assertEquals(
            ErrorCode.INVALID,
            Event.Review(review.id, alice, review.subjectId, 1, 1_000, rating = 5).check(access(alice), 1_000),
        )
        assertEquals(
            ErrorCode.CLOCK_AHEAD,
            Event.Review(review.id, alice, review.subjectId, 1, 3_600_000, rating = 3).check(access(alice), 1_000),
        )

        val settings = Settings(alice, 4, 0, 100, 1)
        assertEquals(null, settings.check(access(alice)))
        assertEquals(ErrorCode.INVALID, settings.copy(grading = 3).check(access(alice)))
        assertEquals(ErrorCode.INVALID, settings.copy(fsrsWeights = "1,2,3").check(access(alice)))
        assertEquals(ErrorCode.INVALID, settings.copy(reminderAt = 24 * 60).check(access(alice)))
        assertEquals(null, settings.copy(reminderAt = 18 * 60 + 30).check(access(alice)))
        assertEquals(Fsrs.DEFAULT, settings.copy(fsrsWeights = Fsrs.DEFAULT.joinToString(",")).weights)
    }
}
