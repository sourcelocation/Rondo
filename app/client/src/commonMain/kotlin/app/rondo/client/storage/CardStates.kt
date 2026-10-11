package app.rondo.client.storage

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.rondo.client.db.Card_state
import app.rondo.core.CardState
import app.rondo.core.DeckLoad
import app.rondo.core.History
import app.rondo.core.Memory
import app.rondo.core.Note
import app.rondo.core.NoteFilter
import app.rondo.core.sql.NoteQuery

/** A card where it stands, in its note's deck. */
class DeckCard(val noteId: String, val card: Int, val deckId: String, val state: CardState)

/** How many cards there are, learned and suspended. */
class Totals(val cards: Int, val learned: Int, val suspended: Int)

/**
 * Every card's state, folded from its events and kept, so lists and sessions read them ready.
 * Never synced: each device folds its own.
 */
class CardStates internal constructor(private val store: Store) {
    private val q get() = store.q

    /** Folds the cards of [notes] again, from their events; a deleted note's cards go. */
    suspend fun refresh(notes: Collection<Note>) {
        if (notes.isEmpty()) return
        val fsrs = store.settings.fsrs()
        val templates = store.templates.all()
        // An answer taken back after it was pushed is voided by an event about that answer.
        val history = History(store.events.of(notes.map { it.id }))
        store.transaction {
            for (n in notes) {
                val template = templates[n.templateId]
                if (n.deletedAt != null || template == null) {
                    q.dropCards(listOf(n.id))
                    continue
                }
                val cards = template.cards(n)
                for (card in cards) q.putCard(row(n, card, history.card(n.id, card).state(fsrs)))
                q.dropCardsOf(n.id, cards.map { it.toLong() })
            }
        }
    }

    /** Folds the cards of the notes [ids] again. */
    suspend fun refresh(ids: Set<String>) = refresh(store.notes.of(ids))

    /** Every card folded again: after new FSRS weights. */
    suspend fun refoldAll() = refresh(store.notes.ids())

    /** The cards of [notes]. */
    suspend fun of(notes: Collection<String>): List<DeckCard> =
        notes.chunked(500).flatMap { q.cardsOfNotes(it).awaitAsList() }.map { it.card() }

    suspend fun of(note: String): List<DeckCard> = q.cardsOf(note).awaitAsList().map { it.card() }

    internal suspend fun drop(notes: Collection<String>) = notes.chunked(500).forEach { q.dropCards(it) }

    // What studying takes ------------------------------------------------------------------------

    /** Cards in [decks] in learning or review, due before [end]. */
    suspend fun due(decks: List<String>, end: Long): List<DeckCard> =
        q.cardsDue(decks, end).awaitAsList().map { it.card() }

    /** As many as [max] new cards in [deck], not buried since [dayStart], in the order they're introduced. */
    suspend fun fresh(deck: String, dayStart: Long, max: Int): List<DeckCard> =
        q.newCards(deck, dayStart, max.toLong()).awaitAsList().map { it.card() }

    /** Reviews in [decks] due from [end] on. */
    suspend fun ahead(decks: List<String>, end: Long): List<DeckCard> =
        q.cardsAhead(decks, end).awaitAsList().map { it.card() }

    /** Each deck's day so far, from [start] to [end] at [now]: what's available and due, what's done, how much is learned. */
    suspend fun loads(start: Long, now: Long, end: Long): Map<String, DeckLoad> {
        val progress = q.progress().awaitAsList().associate { it.deck_id to (it.notes.toInt() to it.learned.toInt()) }
        val loads = q.load(start, now, end).awaitAsList().associate {
            it.deck_id to DeckLoad(
                newAvailable = it.new_available.toInt(),
                learningDue = it.learning_due.toInt(),
                reviewDue = it.review_due.toInt(),
                newToday = it.new_today.toInt(),
                reviewsToday = it.reviews_today.toInt(),
            )
        }.toMutableMap()
        for ((deck, p) in progress) {
            loads[deck] = (loads[deck] ?: DeckLoad()).copy(notes = p.first, learnedNotes = p.second)
        }
        return loads
    }

    /**
     * Per deck, what the cards [filter] picks offer from [start] to [end] at [now]. The cards answered
     * today count wherever they were studied, whatever the filter says of their state.
     */
    suspend fun loads(filter: NoteFilter, start: Long, now: Long, end: Long): Map<String, DeckLoad> {
        val all = NoteQuery(filter.copy(state = null, marked = false)).cards()
        val picked = NoteQuery(filter).card
        val buried = "(c.buried_at IS NULL OR c.buried_at < ?)"
        val sql = "SELECT c.deck_id, " +
            "total(($picked) AND c.suspended = 0 AND c.state = 0 AND $buried), " +
            "total(($picked) AND c.suspended = 0 AND c.state IN (1, 3) AND c.due <= ?), " +
            "total(($picked) AND c.suspended = 0 AND c.state = 2 AND c.due < ? AND $buried), " +
            "total(c.introduced_at >= ?), total(c.state = 2 AND c.last_at >= ? AND c.introduced_at < ?) " +
            "FROM card_state c JOIN notes n ON n.id = c.note_id WHERE ${all.where} GROUP BY c.deck_id"
        val args = listOf<Any>(start, now, end, start, start, start, start) + all.args
        return store.select(sql, args) { r ->
            r.id(0) to DeckLoad(r.total(1), r.total(2), r.total(3), r.total(4), r.total(5))
        }.toMap()
    }

    /** The new cards [filter] picks, not buried since [dayStart]: note, card and deck. */
    suspend fun fresh(filter: NoteFilter, dayStart: Long): List<Triple<String, Int, String>> {
        val f = NoteQuery(filter).cards()
        val sql = "SELECT c.note_id, c.card, c.deck_id FROM card_state c JOIN notes n ON n.id = c.note_id " +
            "WHERE ${f.where} AND c.state = 0 AND c.suspended = 0 AND (c.buried_at IS NULL OR c.buried_at < ?)"
        return store.select(sql, f.args + dayStart) { Triple(it.id(0), it.long(1).toInt(), it.id(2)) }
    }

    /** The cards [filter] picks in learning or review, due before [end] and not buried since [dayStart]. */
    suspend fun due(filter: NoteFilter, dayStart: Long, end: Long): List<DeckCard> = picked(
        filter,
        "c.state > 0 AND c.suspended = 0 AND c.due < ? AND (c.buried_at IS NULL OR c.buried_at < ?)",
        end,
        dayStart,
    )

    /** The reviews [filter] picks, due from [end] on. */
    suspend fun ahead(filter: NoteFilter, end: Long): List<DeckCard> =
        picked(filter, "c.state = 2 AND c.suspended = 0 AND c.due >= ?", end)

    private suspend fun picked(filter: NoteFilter, condition: String, vararg args: Long): List<DeckCard> {
        val f = NoteQuery(filter).cards()
        val sql = "SELECT c.note_id, c.card FROM card_state c JOIN notes n ON n.id = c.note_id " +
            "WHERE ${f.where} AND $condition"
        val keys = store.select(sql, f.args + args.toList()) { it.id(0) to it.long(1).toInt() }.toSet()
        return of(keys.map { it.first }.distinct()).filter { (it.noteId to it.card) in keys }
    }

    // What's coming, and how it went -------------------------------------------------------------

    /** The due times of reviews due from [from] to [to]. */
    suspend fun forecast(from: Long, to: Long): List<Long> = q.forecast(from, to).awaitAsList().mapNotNull { it.due }

    /** Per deck, reviews due before [end] and cards learning, if nothing is studied until then. */
    suspend fun dueBy(end: Long): Map<String, Pair<Int, Int>> = q.dueBy(end).awaitAsList()
        .associate { it.deck_id to (it.review_due.toInt() to it.learning_due.toInt()) }

    suspend fun totals(): Totals = q.cardTotals().awaitAsOne().let {
        Totals(it.cards.toInt(), it.learned.toInt(), it.suspended.toInt())
    }

    private fun row(n: Note, card: Int, s: CardState): Card_state {
        val m = s.memory
        return Card_state(
            n.id, card.toLong(), n.deckId, m.state.toLong(), m.step.toLong(), m.stability, m.difficulty, m.due, m.last,
            m.reps.toLong(), m.lapses.toLong(), s.learnedAt, s.introducedAt, s.suspended, s.buriedAt, s.flag.toLong(),
        )
    }

    private fun Card_state.card(): DeckCard {
        val memory =
            Memory(state.toInt(), step.toInt(), stability, difficulty, due, last_at, reps.toInt(), lapses.toInt())
        return DeckCard(
            note_id,
            card.toInt(),
            deck_id,
            CardState(memory, learned_at, introduced_at, suspended, buried_at, flag.toInt()),
        )
    }
}
