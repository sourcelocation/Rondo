package app.rondo.client

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.rondo.core.Access
import app.rondo.core.Copy
import app.rondo.core.Days
import app.rondo.core.DeckLoad
import app.rondo.core.Decks
import app.rondo.core.Ids
import app.rondo.core.Learning
import app.rondo.core.Level
import app.rondo.core.Positions
import app.rondo.core.Rules
import app.rondo.core.Templates
import app.rondo.core.Tree
import app.rondo.core.model.Deck
import app.rondo.core.model.ErrorCode
import app.rondo.core.model.Event
import app.rondo.core.model.Note
import app.rondo.core.model.Rows
import app.rondo.core.model.Template
import app.rondo.core.nowMillis

/** A change the rules refused, before anything was written. */
class Refused(val code: ErrorCode) : Exception(code.value)

/** Everything a deck list needs at once: the tree, levels and today's counts. */
class Overview(
    val tree: Tree,
    val levels: Map<String, Level>,
    val loads: Map<String, DeckLoad>,
    val lends: Map<String, Int>,
    val me: String,
    /** Levels that just opened by reaching their requirement. */
    val opened: Set<String>,
) {
    fun counts(id: String) = Learning.counts(tree, id, { loads[it] ?: DeckLoad() }, levels)
}

/** Edits to decks, notes and templates, and the personal study actions; all checked by :core first. */
class Library(private val store: Store) {
    private val q = store.q

    suspend fun access(): Access = Access(store.me, store.tree(), store.lends()) { true }

    private fun check(code: ErrorCode?) {
        if (code != null) throw Refused(code)
    }

    suspend fun overview(now: Long = nowMillis()): Overview {
        val loads = loads(now)
        val tree = store.tree()
        val opened = HashSet<String>()
        val unlocked = q.unlocked().awaitAsList().toSet()
        val levels = Learning.levels(tree, { loads[it] ?: DeckLoad() }, unlocked, opened)
        for (deck in opened) unlock(deck)
        return Overview(tree, levels, loads, store.lends(), store.me, opened)
    }

    /** Each deck's day so far: what's available and due, what's done, and how much is learned. */
    suspend fun loads(now: Long = nowMillis()): Map<String, DeckLoad> {
        val days = Days(store.settings().timezone)
        val today = days.start(days.index(now))
        val progress = q.progress().awaitAsList().associate { it.deck_id to (it.notes.toInt() to it.learned.toInt()) }
        val loads = q.load(today, now, today + Days.DAY).awaitAsList().associate {
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

    // Decks --------------------------------------------------------------------------------------

    suspend fun createDeck(name: String, parent: String? = null): Deck {
        val tree = store.tree()
        val siblings = if (parent == null) tree.roots.filter { it.ownerId == store.me } else tree.children(parent)
        val owner = if (parent == null) store.me else tree[parent]?.ownerId
        val position = Positions.between(siblings.lastOrNull()?.position, null)
        return save(null, Decks.new(name.trim(), position, store.clock(), owner, parent))
    }

    suspend fun updateDeck(id: String, change: (Deck) -> Deck): Deck {
        val stored = store.tree()[id] ?: throw Refused(ErrorCode.NOT_FOUND)
        return save(stored, change(stored).copy(v = store.clock()))
    }

    private suspend fun save(stored: Deck?, deck: Deck): Deck {
        check(Rules.deck(access(), stored, deck))
        store.save(deck)
        return deck
    }

    /** Moves [id] under [parent] (null: top level), before [before] or at the end. */
    suspend fun move(id: String, parent: String?, before: String? = null) {
        val tree = store.tree()
        val siblings = (if (parent == null) tree.roots else tree.children(parent)).filter { it.id != id }
        val index = siblings.indexOfFirst { it.id == before }.takeIf { it >= 0 } ?: siblings.size
        val position = Positions.between(siblings.getOrNull(index - 1)?.position, siblings.getOrNull(index)?.position)
        updateDeck(id) { it.copy(parentId = parent, position = position) }
    }

    suspend fun delete(id: String) = updateDeck(id) { it.copy(deletedAt = nowMillis()) }

    suspend fun restore(id: String) = updateDeck(id) { it.copy(deletedAt = null) }

    /**
     * Copies a deck (its notes, templates and your progress) into your own decks; with [convert],
     * notes in Anki's format become Rondo's own on the way.
     */
    suspend fun copy(id: String, parent: String? = null, convert: Boolean = false): String {
        val tree = store.tree()
        val ids = tree.subtree(id).map { it.id }
        val notes = ids.chunked(500).flatMap { q.notesIn(it, Long.MAX_VALUE).awaitAsList() }.map { it.model() }
        val states = notes.flatMap { n ->
            q.cardsOf(n.id).awaitAsList().map { (n.id to it.card.toInt()) to it.model() }
        }.toMap()
        val siblings = if (parent == null) tree.roots else tree.children(parent)
        val position = Positions.between(siblings.lastOrNull()?.position, null)
        val templates = store.templates()
        val rows = Copy.decks(listOf(id), tree, notes, templates, states, store.me, parent, position, store.hlc::next)
        add(if (convert) AnkiText.rows(rows) else rows)
        return rows.decks.orEmpty().first().id
    }

    /**
     * Saves rows made in one go (a copy, an import), each checked like a single edit. Decks must
     * all pass; notes (and their events) that don't are left out and counted.
     */
    suspend fun add(rows: Rows): Int {
        val decks = rows.decks.orEmpty()
        val access = Access(store.me, Tree(store.decks() + decks), store.lends()) { true }
        decks.forEach { check(Rules.deck(access, null, it)) }
        val templates = rows.templates.orEmpty().filter { Rules.template(access, null, it) == null }
        val known = store.templates() + templates.associateBy { it.id }
        val now = nowMillis()
        val notes = rows.notes.orEmpty().filter { Rules.note(access, null, it, known[it.templateId]) == null }
        val kept = notes.map { it.id }.toSet()
        val events = rows.events.orEmpty().filter { it.subjectId in kept && Rules.event(access, it, now) == null }
        store.db.transaction {
            decks.forEach { store.save(it) }
            templates.forEach { store.save(it) }
            notes.forEach { store.save(it, known.getValue(it.templateId)) }
            events.forEach { store.save(it) }
        }
        store.clock()
        store.refreshCards(notes)
        return rows.notes.orEmpty().size - notes.size
    }

    // Notes and templates ------------------------------------------------------------------------

    suspend fun saveNote(note: Note): Note {
        val template = store.template(note.templateId)
        val stored = store.note(note.id)
        val fresh = note.copy(v = store.clock(), ownerId = store.tree()[note.deckId]?.ownerId)
        check(Rules.note(access(), stored, fresh, template))
        store.save(fresh, template!!)
        store.refreshCards(listOf(fresh))
        return fresh
    }

    suspend fun newNote(deck: String, template: String, fields: Map<String, String>): Note =
        saveNote(Note(Ids.new(), deck, template, fields, 0))

    suspend fun deleteNotes(ids: List<String>) = ids.forEach { id ->
        store.note(id)?.let { saveNote(it.copy(deletedAt = nowMillis())) }
    }

    suspend fun restoreNote(id: String) = store.note(id)?.let { saveNote(it.copy(deletedAt = null)) }

    suspend fun moveNotes(ids: List<String>, deck: String) = ids.forEach { id ->
        store.note(id)?.let { saveNote(it.copy(deckId = deck)) }
    }

    suspend fun saveTemplate(t: Template): Template {
        val fresh = t.copy(v = store.clock(), ownerId = store.me)
        val stored = q.template(t.id).awaitAsList().firstOrNull()?.model()
        check(Rules.template(access(), stored, fresh))
        if (fresh.deletedAt != null && q.notesUsing(t.id).awaitAsOne() > 0) throw Refused(ErrorCode.TEMPLATE_IN_USE)
        store.save(fresh)
        return fresh
    }

    // Personal study actions: events ------------------------------------------------------------

    suspend fun event(subject: String, card: Int, kind: Int, value: Int? = null): Event {
        val e = Event(Ids.new(), store.me, subject, card, kind, nowMillis(), value)
        check(Rules.event(access(), e, nowMillis()))
        store.save(e)
        if (kind != Learning.UNLOCK) store.note(subject)?.let { store.refreshCards(listOf(it)) }
        return e
    }

    suspend fun suspendCards(note: String, on: Boolean) = q.cardsOf(note).awaitAsList().forEach {
        event(note, it.card.toInt(), if (on) Learning.SUSPEND else Learning.UNSUSPEND)
    }

    suspend fun flag(note: String, card: Int, color: Int) = event(note, card, Learning.FLAG, color)

    suspend fun bury(note: String, card: Int) = event(note, card, Learning.BURY)

    suspend fun reset(note: String) = q.cardsOf(note).awaitAsList().forEach {
        event(note, it.card.toInt(), Learning.RESET)
    }

    suspend fun unlock(deck: String) = event(deck, 0, Learning.UNLOCK)

    fun isBuiltin(template: String) = Templates.isBuiltin(template)
}
