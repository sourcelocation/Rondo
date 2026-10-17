package app.rondo.client

import app.rondo.client.services.DeckScope
import app.rondo.client.services.FilterScope
import app.rondo.client.services.Scope
import app.rondo.client.services.Session
import app.rondo.core.notes.domain.Filter
import app.rondo.core.notes.domain.Note
import app.rondo.core.notes.domain.NoteFilter
import app.rondo.core.notes.domain.Tags
import app.rondo.core.notes.domain.Template
import app.rondo.core.study.domain.Days
import app.rondo.core.study.domain.Event
import app.rondo.core.study.domain.Memory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking

/** Tags, smart decks and studying a filter, on a device's real database. */
class SmartDecksTest {
    private fun rondo() = runBlocking {
        Rondo.open(JvmPlatform("http://localhost:9", "", ":memory:", null), autoSync = false)
    }

    private suspend fun Rondo.note(deck: String, front: String, tags: String = ""): Note =
        library.newNote(deck, Template.BASIC.id, mapOf("1" to front, "2" to "back"), Tags(tags))

    private fun Rondo.session(scope: suspend () -> Scope) = study.session { listOf(scope()) }

    private fun Rondo.these(filter: Filter) = study.session(study.filter(filter))

    /** Answers everything the session offers with [rating] (Good or Easy: nothing comes back); the fronts, in order. */
    private suspend fun Session.answerAll(rating: Int = 4): List<String> {
        val fronts = ArrayList<String>()
        repeat(100) {
            val card = current ?: return fronts
            fronts += card.note.fields.getValue("1")
            answer(rating, 1_000)
        }
        error("The session never ended")
    }

    @Test
    fun tagsFindTheTagsUnderThemCaseAside() = runBlocking {
        val r = rondo()
        val deck = r.library.createDeck("Medicine").id
        val valves = r.note(deck, "valves", "Cardio::Valves #AK_Step1")
        val cardiology = r.note(deck, "cardiology", "cardiology")
        val renal = r.note(deck, "renal", "Renal #AKxStep1")
        suspend fun find(vararg tags: String) =
            r.store.notes.search(NoteFilter(tags = tags.toList()), 10).map { it.id }.toSet()

        assertEquals(setOf(valves.id), find("cardio"))
        assertEquals(setOf(valves.id, renal.id), find("CARDIO::valves", "renal"))
        // An underscore is itself, not any character.
        assertEquals(setOf(valves.id), find("#AK_Step1"))

        r.library.tag(listOf(cardiology.id, renal.id), listOf("Cardio"), listOf("renal"))
        assertEquals("Cardio cardiology", r.store.notes.get(cardiology.id)!!.tags)
        assertEquals("#AKxStep1 Cardio", r.store.notes.get(renal.id)!!.tags)
        assertEquals(listOf("#AK_Step1", "#AKxStep1", "Cardio", "cardiology"), r.store.notes.tags("", 10))
        assertEquals(listOf("Cardio::Valves"), r.store.notes.tags("valv", 10))
    }

    @Test
    fun aSmartDeckKeepsItsLimitWhateverStudiedItsCards() = runBlocking {
        val r = rondo()
        val deck = r.library.createDeck("Medicine").id
        repeat(5) { r.note(deck, "heart $it", "Cardio") }
        repeat(3) { r.note(deck, "kidney $it", "Renal") }
        val new = Filter(tags = listOf("cardio"), state = "new")
        val smart = r.library.createSmartDeck("Cardio", new).let { s ->
            r.library.updateSmartDeck(s.id) { it.copy(newPerDay = 2) }
        }
        val first = r.session { FilterScope(r.store, smart) }.apply { start() }.answerAll()
        assertEquals(2, first.size)
        assertTrue(first.all { it.startsWith("heart") })
        // They aren't new any more, yet they still count toward today's limit.
        assertEquals(0, r.session { FilterScope(r.store, smart) }.apply { start() }.left)
        // "Study these" has no limit of its own: what was asked for is the limit.
        assertEquals(3, r.these(new).apply { start() }.left)
        // The deck counts them as its own day's too.
        assertEquals(2, r.library.overview().loads.getValue(deck).newToday)
    }

    @Test
    fun lockedLevelsStayLockedWhereverTheyreStudied() = runBlocking {
        val r = rondo()
        val course = r.library.createDeck("Course").id
        r.library.updateDeck(course) { it.copy(gated = true) }
        val one = r.library.createDeck("One", course).id
        val two = r.library.createDeck("Two", course).id
        r.note(two, "later", "x")
        r.note(one, "first", "x")
        val x = Filter(tags = listOf("x"))
        val locked = r.these(x).apply { start() }
        assertEquals(1, locked.left)
        assertEquals("first", locked.current!!.note.fields["1"])
        r.library.unlock(two)
        assertEquals(2, r.these(x).apply { start() }.left)
    }

    @Test
    fun deletedDecksAndOtherNotesStayOut() = runBlocking {
        val r = rondo()
        val kept = r.library.createDeck("Kept").id
        val gone = r.library.createDeck("Gone").id
        r.note(kept, "kept", "x")
        r.note(gone, "gone", "x")
        r.note(kept, "untagged")
        r.library.delete(gone)
        assertEquals(listOf("kept"), r.these(Filter(tags = listOf("x"))).apply { start() }.answerAll())
        // A smart deck of a deck that's gone finds nothing, not everything.
        assertEquals(0, r.these(Filter(deckIds = listOf(gone))).apply { start() }.left)
    }

    @Test
    fun reviewsNotDueYetComeWeakestFirst() = runBlocking {
        val r = rondo()
        val deck = r.library.createDeck("Medicine").id
        val now = Clock.System.now().toEpochMilliseconds()
        val strong = r.note(deck, "strong")
        val weak = r.note(deck, "weak")
        // Reviewed three days ago, due in a few: the weak one less likely to be recalled now.
        for ((n, stability) in listOf(strong to 60.0, weak to 4.0)) {
            val at = now - 3 * Days.DAY
            val due = now + 2 * Days.DAY
            val memory = Memory(Memory.REVIEW, 0, stability, 5.0, due, at, 3, 0)
            r.store.events.save(Event.Snapshot(Uuid.generateV7().toString(), r.store.me, n.id, 1, memory))
        }
        r.store.cards.refresh(listOf(strong, weak))
        val session = r.session { DeckScope(r.store, deck) }.apply { start() }
        assertEquals(0, session.left)
        assertEquals(2, session.aheadLeft())
        session.goAhead()
        assertEquals(listOf("weak", "strong"), session.answerAll(rating = 3))
        assertEquals(0, session.aheadLeft())
    }
}
