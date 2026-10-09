package app.rondo.client

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.rondo.client.db.Card_state
import app.rondo.core.CardSide
import app.rondo.core.CardState
import app.rondo.core.Counts
import app.rondo.core.Days
import app.rondo.core.DeckLoad
import app.rondo.core.Fsrs
import app.rondo.core.Ids
import app.rondo.core.Learning
import app.rondo.core.Memory
import app.rondo.core.Search
import app.rondo.core.Templates
import app.rondo.core.Tree
import app.rondo.core.fold
import app.rondo.core.model.Deck
import app.rondo.core.model.Event
import app.rondo.core.model.Filter
import app.rondo.core.model.Note
import app.rondo.core.model.SmartDeck
import app.rondo.core.model.Template
import app.rondo.core.notes
import app.rondo.core.nowMillis

/** One card in a session. */
class StudyCard(val note: Note, val card: Int, val state: CardState, val deck: Deck, val template: Template) {
    val key get() = "${note.id}:$card"

    fun side(back: Boolean): CardSide = Templates.layout(template, note, card, back)
}

/** Today as scopes see it: the decks with their levels, and when the day began. */
internal class Day(val overview: Overview, val start: Long, val now: Long) {
    val end: Long get() = start + Days.DAY
    val tree: Tree get() = overview.tree

    /** Whether [deck]'s new cards may come: it isn't a locked level. */
    fun open(deck: String): Boolean = overview.levels[deck]?.open != false

    companion object {
        /** Today for [overview], in the learner's time zone. */
        suspend fun of(store: Store, overview: Overview, now: Long = nowMillis()): Day {
            val days = Days(store.settings().timezone)
            return Day(overview, days.start(days.index(now)), now)
        }
    }
}

/**
 * Where a session's cards come from, and how many it takes today. A card is scheduled by its own
 * deck (its retention) wherever it's studied, and counts toward that deck's day.
 */
internal interface Scope {
    /** What it offers today, within its limits. */
    suspend fun counts(day: Day): Counts

    /** Cards in learning or review due by the end of the day, not buried today. */
    suspend fun due(day: Day): List<Card_state>

    /** New cards, as many as [max], in the order they're introduced: open levels only. */
    suspend fun fresh(day: Day, max: Int): List<Pair<String, Int>>

    /** Reviews not due yet. */
    suspend fun ahead(day: Day): List<Card_state>
}

/** A deck with its sub-decks, within the deck's limits. */
internal class DeckScope(private val store: Store, private val id: String) : Scope {
    private fun decks(day: Day) = day.tree.subtree(id).map { it.id }

    override suspend fun counts(day: Day) = day.overview.counts(id)

    override suspend fun due(day: Day) =
        store.q.cardsDue(decks(day), day.end).awaitAsList().filter { (it.buried_at ?: 0) < day.start }

    override suspend fun fresh(day: Day, max: Int): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        for (deck in decks(day).filter(day::open)) {
            if (out.size >= max) break
            store.q.newCards(deck, day.start, (max - out.size).toLong()).awaitAsList()
                .mapTo(out) { it.note_id to it.card.toInt() }
        }
        return out
    }

    override suspend fun ahead(day: Day) = store.q.cardsAhead(decks(day), day.end).awaitAsList()

    companion object {
        /** [id] with its sub-decks; or, null, every deck: each one at the top within its own limits. */
        fun of(store: Store, id: String?, tree: Tree): List<Scope> =
            id?.let { listOf(DeckScope(store, it)) } ?: tree.roots.map { DeckScope(store, it.id) }
    }
}

/**
 * The cards a filter picks from every deck (a smart deck's, or Browse's "Study these"), within
 * [newPerDay] and [reviewsPerDay]. New cards come in deck order, from open levels only. What was
 * answered today counts however it was studied, so a filter that picks new cards still stops at
 * its limit once they're learning.
 */
internal class FilterScope(
    private val store: Store,
    private val filter: Filter,
    private val newPerDay: Int,
    private val reviewsPerDay: Int,
) : Scope {
    override suspend fun counts(day: Day): Counts {
        val f = filter.notes(day.tree)
        val all = Search.cards(f.copy(state = null, marked = false))
        val picked = Search.card(f)
        val buried = "(c.buried_at IS NULL OR c.buried_at < ?)"
        val sql = "SELECT c.deck_id, " +
            "total(($picked) AND c.suspended = 0 AND c.state = 0 AND $buried), " +
            "total(($picked) AND c.suspended = 0 AND c.state IN (1, 3) AND c.due <= ?), " +
            "total(($picked) AND c.suspended = 0 AND c.state = 2 AND c.due < ? AND $buried), " +
            "total(c.introduced_at >= ?), total(c.state = 2 AND c.last_at >= ? AND c.introduced_at < ?) " +
            "FROM card_state c JOIN notes n ON n.id = c.note_id WHERE ${all.where} GROUP BY c.deck_id"
        val args = listOf<Any>(day.start, day.now, day.end, day.start, day.start, day.start, day.start) + all.args
        val loads = store.select(sql, args) { r ->
            fun n(i: Int) = r.getDouble(i)?.toInt() ?: 0
            DeckLoad(if (day.open(r.id(0))) n(1) else 0, n(2), n(3), n(4), n(5))
        }
        val load = loads.fold(DeckLoad()) { a, b ->
            DeckLoad(
                a.newAvailable + b.newAvailable,
                a.learningDue + b.learningDue,
                a.reviewDue + b.reviewDue,
                a.newToday + b.newToday,
                a.reviewsToday + b.reviewsToday,
            )
        }
        return Learning.counts(load, newPerDay, reviewsPerDay)
    }

    override suspend fun due(day: Day) = cards(
        day,
        "c.state > 0 AND c.suspended = 0 AND c.due < ? AND (c.buried_at IS NULL OR c.buried_at < ?)",
        day.end,
        day.start,
    )

    override suspend fun fresh(day: Day, max: Int): List<Pair<String, Int>> {
        val f = Search.cards(filter.notes(day.tree))
        val sql = "SELECT c.note_id, c.card, c.deck_id FROM card_state c JOIN notes n ON n.id = c.note_id " +
            "WHERE ${f.where} AND c.state = 0 AND c.suspended = 0 AND (c.buried_at IS NULL OR c.buried_at < ?)"
        val order = day.tree.roots.flatMap { day.tree.subtree(it.id) }.withIndex().associate { it.value.id to it.index }
        val found = store.select(sql, f.args + day.start) { Triple(it.id(0), it.getLong(1)!!.toInt(), it.id(2)) }
        return found.filter { day.open(it.third) }
            .sortedWith(compareBy({ order[it.third] ?: Int.MAX_VALUE }, { it.first }, { it.second }))
            .take(max).map { it.first to it.second }
    }

    override suspend fun ahead(day: Day) = cards(day, "c.state = 2 AND c.suspended = 0 AND c.due >= ?", day.end)

    /** The filter's cards that also meet [condition]. */
    private suspend fun cards(day: Day, condition: String, vararg args: Long): List<Card_state> {
        val f = Search.cards(filter.notes(day.tree))
        val sql = "SELECT c.note_id, c.card FROM card_state c JOIN notes n ON n.id = c.note_id " +
            "WHERE ${f.where} AND $condition"
        val keys = store.select(sql, f.args + args.toList()) { it.id(0) to it.getLong(1)!!.toInt() }.toSet()
        return keys.map { it.first }.distinct().chunked(500).flatMap { store.q.cardsOfNotes(it).awaitAsList() }
            .filter { (it.note_id to it.card.toInt()) in keys }
    }

    companion object {
        fun of(store: Store, smart: SmartDeck) = FilterScope(store, smart.filter, smart.newPerDay, smart.reviewsPerDay)

        /** Everything the filter picks: what was asked for is the limit. */
        fun unlimited(store: Store, filter: Filter) = FilterScope(store, filter, Int.MAX_VALUE, Int.MAX_VALUE)
    }
}

/**
 * Studying what [scopes] offer, each within its own limits: learning cards when due, then today's
 * reviews most at risk first, then new cards. One card per note per day; cards in learning steps
 * come back within the session. Once that's done, reviews not due yet can follow, weakest first.
 */
class Session internal constructor(
    private val store: Store,
    private val library: Library,
    private val scopes: suspend (Tree) -> List<Scope>,
) {
    /** Studying [deckId] and its sub-decks, or every deck (null). */
    constructor(store: Store, library: Library, deckId: String?) :
        this(store, library, { tree -> DeckScope.of(store, deckId, tree) })

    private val queue = ArrayDeque<Pair<String, Int>>()
    private val later = ArrayList<Pair<Pair<String, Int>, Long>>()
    private var undo: Triple<Event, Pair<String, Int>, Boolean>? = null
    private var day: Day? = null
    private var studied: List<Scope> = emptyList()
    var current: StudyCard? = null
        private set
    var done = 0
        private set
    var opened: Set<String> = emptySet()
        private set
    val left: Int get() = queue.size + later.size + (if (current != null) 1 else 0)

    private suspend fun today(): Day = nowMillis().let { Day.of(store, library.overview(it), it) }

    /** Notes with a card answered today: their other cards wait for tomorrow. */
    private suspend fun seen(day: Day) =
        store.q.reviewsSince(day.start).awaitAsList().map { it.subject_id }.toMutableSet()

    suspend fun start() {
        val day = today().also { day = it }
        val now = day.now
        val seen = seen(day)
        val fsrs = store.fsrs()
        val learning = ArrayList<Pair<Pair<String, Int>, Long>>()
        val reviews = ArrayList<Pair<Pair<String, Int>, Double>>()
        val fresh = ArrayList<Pair<String, Int>>()
        studied = scopes(day.tree)
        for (scope in studied) {
            val counts = scope.counts(day)
            val due = scope.due(day)
            for (c in due.filter { it.state != Memory.REVIEW.toLong() }) {
                learning += (c.note_id to c.card.toInt()) to (c.due ?: now)
            }
            reviews += due.filter { it.state == Memory.REVIEW.toLong() && it.note_id !in seen }
                .map { (it.note_id to it.card.toInt()) to fsrs.retrievability(it.model().memory, now) }
                .sortedBy { it.second }.take(counts.review)
            var room = counts.new
            for (key in scope.fresh(day, room * 4)) {
                if (room <= 0) break
                if (!seen.add(key.first)) continue
                fresh += key
                room--
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

    /** Reviews not due yet from notes not studied today, the most at risk first. */
    private suspend fun ahead(day: Day): List<Pair<String, Int>> {
        val seen = seen(day)
        val fsrs = store.fsrs()
        val waiting = (queue + later.map { it.first } + listOfNotNull(current?.let { it.note.id to it.card })).toSet()
        return studied.flatMap { it.ahead(day) }.filter { it.note_id !in seen }
            .map { (it.note_id to it.card.toInt()) to fsrs.retrievability(it.model().memory, day.now) }
            .filter { it.first !in waiting }.sortedBy { it.second }.map { it.first }.distinctBy { it.first }
    }

    /** How many reviews could still come early, once today's are done. */
    suspend fun aheadLeft(): Int = day?.let { ahead(it).size } ?: 0

    /** Goes on with up to [count] reviews not due yet, weakest first: for before an exam. */
    suspend fun goAhead(count: Int = AHEAD) {
        val day = today().also { day = it }
        queue += ahead(day).take(count)
        if (current == null) next()
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

        /** Reviews taken early at a time. */
        const val AHEAD = 50

        /** Typed answers match ignoring case, accents and surrounding space. */
        fun typedCorrectly(typed: String, expected: String) = fold(typed).trim() == fold(expected).trim()
    }
}
