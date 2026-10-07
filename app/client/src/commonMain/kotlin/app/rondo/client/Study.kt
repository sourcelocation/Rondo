package app.rondo.client

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.rondo.core.CardSide
import app.rondo.core.CardState
import app.rondo.core.Days
import app.rondo.core.Fsrs
import app.rondo.core.Ids
import app.rondo.core.Learning
import app.rondo.core.Memory
import app.rondo.core.Templates
import app.rondo.core.fold
import app.rondo.core.model.Deck
import app.rondo.core.model.Event
import app.rondo.core.model.Note
import app.rondo.core.model.Template
import app.rondo.core.nowMillis

/** One card in a session. */
class StudyCard(val note: Note, val card: Int, val state: CardState, val deck: Deck, val template: Template) {
    val key get() = "${note.id}:$card"

    fun side(back: Boolean): CardSide = Templates.layout(template, note, card, back)
}

/**
 * Studying [deckId] (null: every deck): learning cards when due, then today's reviews most at risk
 * first, then new cards from open levels in deck order, within the deck's limits. One card per
 * note per day; cards in learning steps come back within the session.
 */
class Session(private val store: Store, private val library: Library, private val deckId: String?) {
    private val queue = ArrayDeque<Pair<String, Int>>()
    private val later = ArrayList<Pair<Pair<String, Int>, Long>>()
    private var undo: Triple<Event, Pair<String, Int>, Boolean>? = null
    var current: StudyCard? = null
        private set
    var done = 0
        private set
    var opened: Set<String> = emptySet()
        private set
    val left: Int get() = queue.size + later.size + (if (current != null) 1 else 0)

    suspend fun start() {
        val now = nowMillis()
        val overview = library.overview(now)
        val days = Days(store.settings().timezone)
        val today = days.start(days.index(now))
        val tree = overview.tree
        val seen = store.q.reviewsSince(today).awaitAsList().map { it.subject_id }.toMutableSet()
        val fsrs = store.fsrs()
        val roots = deckId?.let(::listOf) ?: tree.roots.map { it.id }
        val learning = ArrayList<Pair<Pair<String, Int>, Long>>()
        val reviews = ArrayList<Pair<Pair<String, Int>, Double>>()
        val fresh = ArrayList<Pair<String, Int>>()
        for (root in roots) {
            val counts = overview.counts(root)
            val decks = tree.subtree(root)
            val due = store.q.cardsDue(decks.map { it.id }, today + Days.DAY).awaitAsList().filter {
                (it.buried_at ?: 0) < today
            }
            for (c in due.filter { it.state != Memory.REVIEW.toLong() }) {
                learning += (c.note_id to c.card.toInt()) to (c.due ?: now)
            }
            reviews += due.filter { it.state == Memory.REVIEW.toLong() && it.note_id !in seen }
                .map { (it.note_id to it.card.toInt()) to fsrs.retrievability(it.model().memory, now) }
                .sortedBy { it.second }.take(counts.review)
            var room = counts.new
            for (d in decks) {
                if (room <= 0 || overview.levels[d.id]?.open == false) continue
                for (c in store.q.newCards(d.id, today, room.toLong() * 4).awaitAsList()) {
                    if (room <= 0 || !seen.add(c.note_id)) continue
                    fresh += c.note_id to c.card.toInt()
                    room--
                }
            }
        }
        queue.clear()
        later.clear()
        queue += learning.filter { it.second <= now }.sortedBy { it.second }.map { it.first }
        later += learning.filter { it.second > now }
        queue += reviews.map { it.first }.distinctBy { it.first }
        queue += fresh
        next()
    }

    private suspend fun next() {
        val now = nowMillis()
        val ready = later.filter { it.second <= now + LEARN_AHEAD }.minByOrNull { it.second }
        // A learning card comes back when due, or a little early when nothing else is left.
        val key = if (ready != null && (queue.isEmpty() || ready.second <= now)) {
            ready.first.also { later.remove(ready) }
        } else {
            queue.removeFirstOrNull()
        }
        current = key?.let { load(it) }
        if (current == null && key != null) next()
    }

    private suspend fun load(key: Pair<String, Int>): StudyCard? {
        val note = store.note(key.first)?.takeIf { it.deletedAt == null } ?: return null
        val state =
            store.q.cardsOf(note.id).awaitAsList().firstOrNull { it.card.toInt() == key.second }?.model() ?: return null
        val deck = store.tree()[note.deckId] ?: return null
        return StudyCard(note, key.second, state, deck, store.template(note.templateId) ?: return null)
    }

    private suspend fun scheduler(deck: Deck) =
        Fsrs(Fsrs.parse(store.settings().fsrsWeights), deck.retention / 100.0, Days(store.settings().timezone))

    /** How long until the card comes back for each rating (1 Again … 4 Easy), in milliseconds. */
    suspend fun intervals(): List<Long> {
        val card = current ?: return emptyList()
        val now = nowMillis()
        val fsrs = scheduler(card.deck)
        return (1..4).map { (fsrs.review(card.key, card.state.memory, it, now).due ?: now) - now }
    }

    suspend fun answer(rating: Int, durationMs: Int) {
        val card = current ?: return
        val now = nowMillis()
        val memory = scheduler(card.deck).review(card.key, card.state.memory, rating, now)
        val event =
            Event(Ids.new(), store.me, card.note.id, card.card, Learning.REVIEW, now, rating, durationMs, memory.due)
        store.save(event)
        store.refreshCards(listOf(card.note))
        val key = card.note.id to card.card
        val back = memory.due?.takeIf { memory.state != Memory.REVIEW }
        if (back != null) later += key to back
        undo = Triple(event, key, back != null)
        done++
        opened = library.overview(now).opened
        next()
    }

    val canUndo: Boolean get() = undo != null

    /** Reads the card on screen again, after its note was edited. */
    suspend fun reload() {
        val c = current ?: return
        current = load(c.note.id to c.card)
    }

    /** Takes the last answer back: its event goes (or is voided once pushed) and the card returns. */
    suspend fun undo() {
        val (event, key, requeued) = undo ?: return
        undo = null
        val pushed = store.q.eventsOf(event.subjectId).awaitAsList().firstOrNull { it.id == event.id }?.pushed ?: false
        if (pushed) {
            store.save(
                Event(Ids.new(), store.me, event.id, 0, Learning.VOID, nowMillis()),
            )
        } else {
            store.q.dropUnpushedEvent(event.id)
        }
        store.note(event.subjectId)?.let { store.refreshCards(listOf(it)) }
        if (requeued) later.removeAll { it.first == key }
        current?.let { queue.addFirst(it.note.id to it.card) }
        current = load(key)
        done--
    }

    companion object {
        /** Learning cards come back early when nothing else is left, as in Anki. */
        const val LEARN_AHEAD = 20 * 60_000L

        /** Typed answers match ignoring case, accents and surrounding space. */
        fun typedCorrectly(typed: String, expected: String) = fold(typed).trim() == fold(expected).trim()
    }
}
