package app.rondo.client.services

import app.rondo.client.storage.DeckCard
import app.rondo.client.storage.Store
import app.rondo.client.storage.Totals
import app.rondo.core.decks.domain.Counts
import app.rondo.core.decks.domain.Deck
import app.rondo.core.decks.domain.DeckLoad
import app.rondo.core.decks.domain.Tree
import app.rondo.core.markup.domain.Folded
import app.rondo.core.notes.domain.CardSide
import app.rondo.core.notes.domain.Filter
import app.rondo.core.notes.domain.Note
import app.rondo.core.notes.domain.Template
import app.rondo.core.study.domain.CardState
import app.rondo.core.study.domain.Days
import app.rondo.core.study.domain.Event
import app.rondo.core.study.domain.Memory
import app.rondo.core.study.domain.SmartDeck
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** One card in a session. */
class StudyCard(val note: Note, val card: Int, val state: CardState, val deck: Deck, val template: Template) {
    val key get() = "${note.id}:$card"

    fun side(back: Boolean): CardSide = template.side(note, card, back)
}

/** Today as scopes see it: the decks with their levels, and when the day began. */
class Day internal constructor(val overview: Overview, val start: Long, val now: Long) {
    val end: Long get() = start + Days.DAY
    val tree: Tree get() = overview.tree

    /** Whether [deck]'s new cards may come: it isn't a locked level. */
    fun open(deck: String): Boolean = overview.levels[deck]?.open != false
}

/**
 * Where a session's cards come from, and how many it takes today. A card is scheduled by its own
 * deck (its retention) wherever it's studied, and counts toward that deck's day.
 */
interface Scope {
    /** What it offers today, within its limits. */
    suspend fun counts(day: Day): Counts

    /** Cards in learning or review due by the end of the day, not buried today. */
    suspend fun due(day: Day): List<DeckCard>

    /** New cards, as many as [max], in the order they're introduced: open levels only. */
    suspend fun fresh(day: Day, max: Int): List<Pair<String, Int>>

    /** Reviews not due yet. */
    suspend fun ahead(day: Day): List<DeckCard>
}

/** A deck with its sub-decks, within the deck's limits. */
class DeckScope(private val store: Store, private val id: String) : Scope {
    private fun decks(day: Day) = day.tree.subtree(id).map { it.id }

    override suspend fun counts(day: Day) = day.overview.counts(id)

    override suspend fun due(day: Day) =
        store.cards.due(decks(day), day.end).filter { (it.state.buriedAt ?: 0) < day.start }

    override suspend fun fresh(day: Day, max: Int): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        for (deck in decks(day).filter(day::open)) {
            if (out.size >= max) break
            store.cards.fresh(deck, day.start, max - out.size).mapTo(out) { it.noteId to it.card }
        }
        return out
    }

    override suspend fun ahead(day: Day) = store.cards.ahead(decks(day), day.end)
}

/**
 * The cards a filter picks from every deck (a smart deck's, or Browse's "Study these"), within
 * [newPerDay] and [reviewsPerDay]. New cards come in deck order, from open levels only. What was
 * answered today counts however it was studied, so a filter that picks new cards still stops at
 * its limit once they're learning.
 */
class FilterScope(
    private val store: Store,
    private val filter: Filter,
    private val newPerDay: Int = Int.MAX_VALUE,
    private val reviewsPerDay: Int = Int.MAX_VALUE,
) : Scope {
    /** A smart deck's cards, within its own limits. */
    constructor(store: Store, smart: SmartDeck) : this(store, smart.filter, smart.newPerDay, smart.reviewsPerDay)

    override suspend fun counts(day: Day): Counts {
        val loads = store.cards.loads(filter.notes(day.tree), day.start, day.now, day.end)
        val load = loads.entries.fold(DeckLoad()) { a, (deck, b) ->
            DeckLoad(
                a.newAvailable + if (day.open(deck)) b.newAvailable else 0,
                a.learningDue + b.learningDue,
                a.reviewDue + b.reviewDue,
                a.newToday + b.newToday,
                a.reviewsToday + b.reviewsToday,
            )
        }
        return load.counts(newPerDay, reviewsPerDay)
    }

    override suspend fun due(day: Day) = store.cards.due(filter.notes(day.tree), day.start, day.end)

    override suspend fun fresh(day: Day, max: Int): List<Pair<String, Int>> {
        val order = day.tree.roots.flatMap { day.tree.subtree(it.id) }.withIndex().associate { it.value.id to it.index }
        return store.cards.fresh(filter.notes(day.tree), day.start).filter { day.open(it.third) }
            .sortedWith(compareBy({ order[it.third] ?: Int.MAX_VALUE }, { it.first }, { it.second }))
            .take(max).map { it.first to it.second }
    }

    override suspend fun ahead(day: Day) = store.cards.ahead(filter.notes(day.tree), day.end)
}

/**
 * Studying, and what it reads: today's day, and sessions over scopes ([deck], [smart], [filter]).
 */
class Study(private val store: Store, private val library: Library) {
    private fun now() = Clock.System.now().toEpochMilliseconds()

    /** Today for [overview], in the learner's time zone. */
    suspend fun day(overview: Overview, now: Long = now()): Day {
        val days = Days(store.settings.get().timezone)
        return Day(overview, days.start(days.index(now)), now)
    }

    /** [id] with its sub-decks; or, null, every deck: each one at the top within its own limits. */
    fun deck(id: String?): suspend (Tree) -> List<Scope> = { tree ->
        id?.let { listOf(DeckScope(store, it)) } ?: tree.roots.map { DeckScope(store, it.id) }
    }

    /** A smart deck, within its limits; nothing once it's deleted. */
    fun smart(id: String): suspend (Tree) -> List<Scope> = {
        listOfNotNull(store.smartDecks.get(id)?.takeIf { it.deletedAt == null }?.let { FilterScope(store, it) })
    }

    /** Everything [filter] picks: what was asked for is the limit. */
    fun filter(filter: Filter): suspend (Tree) -> List<Scope> = { listOf(FilterScope(store, filter)) }

    fun session(scopes: suspend (Tree) -> List<Scope>) = Session(store, library, this, scopes)

    /** What [smart] offers on [day], within its limits. */
    suspend fun counts(smart: SmartDeck, day: Day): Counts = FilterScope(store, smart).counts(day)

    /** What everything [filter] picks offers on [day]. */
    suspend fun counts(filter: Filter, day: Day): Counts = FilterScope(store, filter).counts(day)

    /** A year of answers a day (oldest first, today last), the streak they make, and tomorrow's reviews. */
    suspend fun activity(now: Long = now()): Activity {
        val days = Days(store.settings.get().timezone)
        val today = days.index(now)
        val perDay = IntArray(365)
        for (r in store.events.reviewsSince(days.start(today - 364))) {
            (days.index(r.at) - (today - 364)).toInt().takeIf { it in 0..364 }?.let { perDay[it]++ }
        }
        val streak = perDay.reversed().let { d -> (if (d[0] == 0) d.drop(1) else d).takeWhile { it > 0 }.size }
        val tomorrow = store.cards.forecast(days.start(today + 1), days.start(today + 2) - 1).size
        return Activity(perDay, streak, tomorrow)
    }

    /**
     * How studying goes: the last 365 days' answers ([Activity]), true retention (answers to cards
     * last seen at least a day before, over the last 30 days), minutes spent then, and reviews due
     * on each of the next 30 days.
     */
    suspend fun insights(now: Long = now()): Insights {
        val days = Days(store.settings.get().timezone)
        val today = days.index(now)
        val reviews = store.events.reviewsSince(days.start(today - 364))
        val monthStart = now - 30 * Days.DAY
        var passed = 0
        var total = 0
        var ms = 0L
        for ((_, list) in reviews.groupBy { it.subjectId to it.card }) {
            list.zipWithNext().forEach { (a, b) ->
                if (b.at >= monthStart && b.at - a.at >= 20 * Days.HOUR) {
                    total++
                    if (b.rating > 1) passed++
                }
            }
            ms += list.filter { it.at >= monthStart }.sumOf { it.durationMs ?: 0 }
        }
        val forecast = IntArray(30)
        for (due in store.cards.forecast(0, days.start(today + 30))) {
            val day = (days.index(due) - today).toInt().coerceAtLeast(0)
            if (day < 30) forecast[day]++
        }
        val retention = if (total >= 10) (100.0 * passed / total).roundToInt() else null
        return Insights(activity(now), retention, (ms / 60_000).toInt(), forecast, store.cards.totals())
    }
}

/** A year of answers a day (oldest first, today last), the streak they make, and tomorrow's reviews. */
class Activity(val days: IntArray, val streak: Int, val tomorrow: Int)

/** See [Study.insights]. */
class Insights(
    val activity: Activity,
    val retention: Int?,
    val minutes: Int,
    val forecast: IntArray,
    val totals: Totals,
)

/**
 * Studying what [scopes] offer, each within its own limits: learning cards when due, then today's
 * reviews most at risk first, then new cards. One card per note per day; cards in learning steps
 * come back within the session. Once that's done, reviews not due yet can follow, weakest first.
 */
class Session internal constructor(
    private val store: Store,
    private val library: Library,
    private val study: Study,
    private val scopes: suspend (Tree) -> List<Scope>,
) {
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

    private fun now() = Clock.System.now().toEpochMilliseconds()

    private suspend fun today(): Day = now().let { study.day(library.overview(it), it) }

    /** Notes with a card answered today: their other cards wait for tomorrow. */
    private suspend fun seen(day: Day) = store.events.reviewsSince(day.start).map { it.subjectId }.toMutableSet()

    suspend fun start() {
        val day = today().also { day = it }
        val now = day.now
        val seen = seen(day)
        val fsrs = store.settings.fsrs()
        val learning = ArrayList<Pair<Pair<String, Int>, Long>>()
        val reviews = ArrayList<Pair<Pair<String, Int>, Double>>()
        val fresh = ArrayList<Pair<String, Int>>()
        studied = scopes(day.tree)
        for (scope in studied) {
            val counts = scope.counts(day)
            val due = scope.due(day)
            for (c in due.filter { it.state.memory.state != Memory.REVIEW }) {
                learning += (c.noteId to c.card) to (c.state.memory.due ?: now)
            }
            reviews += due.filter { it.state.memory.state == Memory.REVIEW && it.noteId !in seen }
                .map { (it.noteId to it.card) to fsrs.retrievability(it.state.memory, now) }
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
        val fsrs = store.settings.fsrs()
        val waiting = (queue + later.map { it.first } + listOfNotNull(current?.let { it.note.id to it.card })).toSet()
        return studied.flatMap { it.ahead(day) }.filter { it.noteId !in seen }
            .map { (it.noteId to it.card) to fsrs.retrievability(it.state.memory, day.now) }
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
        val now = now()
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
        val note = store.notes.get(key.first)?.takeIf { it.deletedAt == null } ?: return null
        val state = store.cards.of(note.id).firstOrNull { it.card == key.second }?.state ?: return null
        val deck = store.decks.get(note.deckId) ?: return null
        return StudyCard(note, key.second, state, deck, store.templates.get(note.templateId) ?: return null)
    }

    /** How long until the card comes back for each rating (1 Again … 4 Easy), in milliseconds. */
    suspend fun intervals(): List<Long> {
        val card = current ?: return emptyList()
        val now = now()
        val fsrs = store.settings.fsrs(card.deck.retention, calendar = true)
        return (1..4).map { (fsrs.review(card.key, card.state.memory, it, now).due ?: now) - now }
    }

    suspend fun answer(rating: Int, durationMs: Int) {
        val card = current ?: return
        val now = now()
        val memory = store.settings.fsrs(
            card.deck.retention,
            calendar = true,
        ).review(card.key, card.state.memory, rating, now)
        val event = Event.Review(
            Uuid.generateV7().toString(),
            store.me,
            card.note.id,
            card.card,
            now,
            rating,
            durationMs,
            memory.due,
        )
        library.record(event)
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
        if (!store.events.forget(event)) {
            library.record(Event.Void(Uuid.generateV7().toString(), store.me, event.id, now()))
        }
        store.notes.get(event.subjectId)?.let { store.cards.refresh(listOf(it)) }
        if (requeued) later.removeAll { it.first == key }
        current?.let { queue.addFirst(it.note.id to it.card) }
        current = load(key)
        done--
    }

    /** Whether [typed] is the [expected] answer: case, accents and surrounding space aside. */
    fun correct(typed: String, expected: String) = Folded(typed).text.trim() == Folded(expected).text.trim()

    private companion object {
        /** Learning cards come back early when nothing else is left, as in Anki. */
        const val LEARN_AHEAD = 20 * 60_000L

        /** Reviews taken early at a time. */
        const val AHEAD = 50
    }
}
