package app.rondo.client.services

import app.rondo.client.anki.AnkiConversion
import app.rondo.client.storage.DeckCard
import app.rondo.client.storage.Derived
import app.rondo.client.storage.Store
import app.rondo.core.Access
import app.rondo.core.Days
import app.rondo.core.Deck
import app.rondo.core.DeckLoad
import app.rondo.core.ErrorCode
import app.rondo.core.Event
import app.rondo.core.Filter
import app.rondo.core.History
import app.rondo.core.Level
import app.rondo.core.Note
import app.rondo.core.NoteFilter
import app.rondo.core.NoteSort
import app.rondo.core.Position
import app.rondo.core.Refused
import app.rondo.core.Settings
import app.rondo.core.SmartDeck
import app.rondo.core.Tags
import app.rondo.core.Template
import app.rondo.core.Tree
import app.rondo.core.file.Contents
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    fun counts(id: String) = tree.counts(id, { loads[it] ?: DeckLoad() }, levels)
}

/**
 * What adding decks did: [deck] is the first at the top; [notes] were added, [there] were there
 * from an earlier import, and [refused] broke a rule.
 */
class Added(val deck: String?, val notes: Int, val there: Int, val refused: Int)

/**
 * What adding decks would do ([Library.plan]): the [decks], note types and notes ([notes], each
 * with its note type) that are new, and their [events]. [placed] says where each deck in hand goes,
 * new or there from before; [there] notes were there already, and [refused] broke a rule.
 */
class Plan internal constructor(
    val first: String?,
    val decks: List<Deck>,
    val templates: List<Template>,
    val notes: List<Pair<Note, Template>>,
    val events: List<Event>,
    val placed: Map<String, String>,
    val there: Int,
    val refused: Int,
    /** Each new note's source, by its id. */
    val from: Map<String, Contents.Entry>,
)

/**
 * Your decks, note types, notes and smart decks, and your own study actions. Every change is
 * checked by the thing it changes before it's kept, and then synced.
 */
class Library(private val store: Store) {
    private fun now() = Clock.System.now().toEpochMilliseconds()

    /** Changes to a stored row, one at a time: two read the same row and the second undoes the first otherwise. */
    private val changing = Mutex()

    suspend fun access(): Access = Access(store.me, store.decks.tree(), store.lends.roles()) { true }

    /** Every deck, deleted ones included, as a tree. */
    suspend fun tree(): Tree = store.decks.tree()

    /** Whose this library is: the account, or before signing in a placeholder. */
    val me: String get() = store.me

    /** Each lent deck's lender's name, by deck. */
    suspend fun lenders(): Map<String, String?> = store.lends.all().associate { it.deckId to it.ownerName }

    /** Your notes deleted lately, the latest first. */
    suspend fun deletedNotes(): List<Note> = store.notes.deleted(store.me)

    /** The decks holding live notes that keep Anki's format. */
    suspend fun ankiDecks(): Set<String> = store.notes.ankiDecks()

    /** Every note type by id: Rondo's own, then those on this device. */
    suspend fun templates(): Map<String, Template> = store.templates.all()

    suspend fun template(id: String): Template? = store.templates.get(id)

    /** How many live notes use each note type. */
    suspend fun templateUse(): Map<String, Int> = store.templates.use()

    /** Your smart decks, in their order. */
    suspend fun smartDecks(): List<SmartDeck> = store.smartDecks.all()

    suspend fun smartDeck(id: String): SmartDeck? = store.smartDecks.get(id)

    /** Notes [filter] finds, in [sort] order (see [app.rondo.client.storage.NoteShelf.search]). */
    suspend fun search(filter: NoteFilter, limit: Int, sort: NoteSort = NoteSort.ADDED): List<Note> =
        store.notes.search(filter, limit, sort)

    /** Tags notes have that contain [text], with the tags above them; with no text, the top-level ones. */
    suspend fun tags(text: String, limit: Int): List<String> = store.notes.tags(text, limit)

    /** The cards of [notes], where they stand. */
    suspend fun cards(notes: Collection<String>): List<DeckCard> = store.cards.of(notes)

    /** A small value this device keeps for itself: what was last picked, and the like. */
    suspend fun remembered(key: String): String? = store.meta(key)

    suspend fun remember(key: String, value: String) = store.putMeta(key, value)

    /** The learner's settings. */
    suspend fun settings(): Settings = store.settings.get()

    /** Changes the learner's settings, checked first. */
    suspend fun settings(change: (Settings) -> Settings): Settings {
        val changed = change(store.settings.get()).copy(v = store.clock())
        check(changed.check(access()))
        store.settings.save(changed)
        return changed
    }

    private fun check(code: ErrorCode?) {
        if (code != null) throw Refused(code)
    }

    /** The tree, each deck's level and today so far; levels that just opened are unlocked. */
    suspend fun overview(now: Long = now()): Overview {
        val loads = loads(now)
        val tree = store.decks.tree()
        val opened = HashSet<String>()
        val levels = tree.levels({ loads[it] ?: DeckLoad() }, store.events.unlocked(), opened)
        for (deck in opened) unlock(deck)
        return Overview(tree, levels, loads, store.lends.roles(), store.me, opened)
    }

    /** Each deck's day so far: what's available and due, what's done, and how much is learned. */
    suspend fun loads(now: Long = now()): Map<String, DeckLoad> {
        val days = Days(store.settings.get().timezone)
        val today = days.start(days.index(now))
        return store.cards.loads(today, now, today + Days.DAY)
    }

    // Decks --------------------------------------------------------------------------------------

    suspend fun createDeck(name: String, parent: String? = null): Deck {
        val tree = store.decks.tree()
        val owner = if (parent == null) store.me else tree[parent]?.ownerId
        val position = Position(tree.siblings(parent, store.me).lastOrNull()?.position, null).key
        return save(null, Deck(name.trim(), position, store.clock(), owner, parent))
    }

    suspend fun updateDeck(id: String, change: (Deck) -> Deck): Deck = changing.withLock {
        val stored = store.decks.get(id) ?: throw Refused(ErrorCode.NOT_FOUND)
        save(stored, change(stored).copy(v = store.clock()))
    }

    private suspend fun save(stored: Deck?, deck: Deck): Deck {
        check(deck.check(access(), stored))
        store.decks.save(deck)
        return deck
    }

    /** Moves [id] under [parent] (null: top level), before [before] or at the end. */
    suspend fun move(id: String, parent: String?, before: String? = null) {
        val tree = store.decks.tree()
        val siblings = (if (parent == null) tree.roots else tree.children(parent)).filter { it.id != id }
        val index = siblings.indexOfFirst { it.id == before }.takeIf { it >= 0 } ?: siblings.size
        val position = Position(siblings.getOrNull(index - 1)?.position, siblings.getOrNull(index)?.position).key
        updateDeck(id) { it.copy(parentId = parent, position = position) }
    }

    suspend fun delete(id: String) = updateDeck(id) { it.copy(deletedAt = now()) }

    suspend fun restore(id: String) = updateDeck(id) { it.copy(deletedAt = null) }

    /**
     * Deck [id] in hand: its live sub-decks, their live notes with their note types, and with
     * [events] the events of its cards and decks.
     */
    suspend fun open(id: String, events: Boolean = false): Contents {
        val tree = store.decks.tree()
        val root = tree[id]?.takeIf { !tree.dead(id) } ?: throw Refused(ErrorCode.NOT_FOUND)
        val decks = tree.subtree(id)
        val notes = store.notes.inDecks(decks.map { it.id })
        val templates = store.templates.all()
        val history = History(if (events) store.events.of(notes.map { it.id } + decks.map { it.id }) else emptyList())
        val byDeck = notes.groupBy { it.deckId }
        fun contents(d: Deck): Contents = Contents(
            d,
            byDeck[d.id].orEmpty().mapNotNull { n ->
                templates[n.templateId]?.let { Contents.Entry(n, it, history.of(n.id)) }
            },
            tree.children(d.id).map(::contents),
            history.of(d.id),
        )
        return contents(root)
    }

    /**
     * Copies a deck (its notes, note types and your progress) into your own decks; with [convert],
     * notes in Anki's format become Rondo's own on the way. The copy's id.
     */
    suspend fun copy(id: String, parent: String? = null, convert: Boolean = false): String {
        val opened = open(id)
        // Your reviews stay with the original: the copy starts where each card stands.
        val states = store.cards.of(opened.all.flatMap { c -> c.notes.map { it.note.id } }).groupBy { it.noteId }
        val now = now()
        val progress = opened.map(
            entry = { e ->
                val snapshots = states[e.note.id].orEmpty().mapNotNull {
                    it.state.snapshot(e.note.id, it.card, store.me, now)
                }
                Contents.Entry(e.note, e.template, snapshots)
            },
        )
        val copy = if (convert) AnkiConversion(listOf(progress)).contents.single() else progress
        return add(listOf(copy), parent, fresh = true).deck ?: throw Refused(ErrorCode.NOT_FOUND)
    }

    /**
     * What adding decks in hand ([contents]: a file's, or a copy) as your own, under [parent] (null:
     * the top level), would do: see [add], which keeps it. Each deck, note type, note and event gets
     * an id of its own, made from its source's and the place it goes, so adding the same again to
     * the same place finds what's there from before and adds only what isn't. [fresh] makes every
     * id new instead: a copy beside any other. Note types of yours, and Rondo's own, are used as
     * they are. Decks must all pass their checks; notes (with their events) that don't are left out
     * and counted.
     */
    suspend fun plan(contents: List<Contents>, parent: String?, fresh: Boolean = false): Plan {
        val me = store.me
        val tree = store.decks.tree()
        if (parent != null && tree[parent]?.ownerId != me) throw Refused(ErrorCode.FORBIDDEN)
        val ids = Derived(if (fresh) Uuid.random().toString() else "$me/${parent.orEmpty()}")
        val now = now()

        // Decks: one there from before, or a new one. A new deck under one already there goes after its
        // decks; under a new one, it keeps its place in the file.
        val decks = HashMap<String, String>()
        val made = ArrayList<Deck>()
        val there = tree.byId.values.filter { !tree.dead(it.id) && it.ownerId == me }.associateBy { ids.tail(it.id) }
        val last = HashMap<String?, String?>()
        fun place(c: Contents, above: String?) {
            val id = ids.of(c.deck.id)
            val found = there[ids.tail(id)]
            if (found != null) {
                decks[c.deck.id] = found.id
            } else {
                val position = if (above == parent || tree[above] != null) {
                    val after = if (above in last) last[above] else tree.siblings(above, me).lastOrNull()?.position
                    Position(after, null).key.also { last[above] = it }
                } else {
                    c.deck.position
                }
                made +=
                    c.deck.copy(
                        id = id,
                        ownerId = me,
                        parentId = above,
                        position = position,
                        v = store.hlc.next(),
                        deletedAt = null,
                    )
                decks[c.deck.id] = id
            }
            c.decks.forEach { place(it, decks.getValue(c.deck.id)) }
        }
        contents.forEach { place(it, parent) }

        // Note types: yours and Rondo's own as they are; others as yours, there from before or new.
        val stored = store.templates.all()
        val yours = stored.values.filter { it.ownerId == me && it.deletedAt == null }.associateBy { ids.tail(it.id) }
        val templates = HashMap<String, Template>()
        val newTemplates = ArrayList<Template>()
        for (t in contents.flatMap { c -> c.all.flatMap { d -> d.notes.map { it.template } } }.distinctBy { it.id }) {
            val own = stored[t.id]?.takeIf { it.builtin || (it.ownerId == me && it.deletedAt == null) }
            templates[t.id] = own ?: yours[ids.tail(ids.of(t.id))]
                ?: t.with(id = ids.of(t.id), v = store.hlc.next(), ownerId = me, deletedAt = null).also {
                    newTemplates +=
                        it
                }
        }

        // Notes, less those there from before, with the events of their cards.
        val noteTails = store.notes.ids().map(ids::tail).toSet()
        val entries = contents.flatMap { c -> c.all.flatMap { d -> d.notes } }
        // Events already on this device (your own export, added back) come as where each card stands.
        val here = store.events.any(entries.flatMap { e -> e.events.map { it.id } })
        val fsrs = store.settings.fsrs()
        var repeated = 0
        val notes = ArrayList<Note>()
        val events = ArrayList<Event>()
        val from = HashMap<String, Contents.Entry>()
        for (e in entries) {
            val id = ids.of(e.note.id)
            if (ids.tail(id) in noteTails) {
                repeated++
                continue
            }
            from[id] = e
            val template = templates.getValue(e.template.id)
            notes += e.note.copy(
                id = id,
                deckId = decks.getValue(e.note.deckId),
                templateId = template.id,
                ownerId = me,
                v = store.hlc.next(),
                deletedAt = null,
            )
            val own = if (here) snapshots(e, fsrs, now) else e.events
            events += own.map { ev ->
                val subject = if (ev is Event.Void) ids.of(ev.subjectId) else id
                ev.with(ids.of(ev.id), me, subject)
            }
        }
        // Levels opened by hand.
        for (c in contents.flatMap { it.all }) {
            events +=
                c.events.filterIsInstance<Event.Unlock>().map { it.with(ids.of(it.id), me, decks.getValue(c.deck.id)) }
        }

        val access = Access(me, Tree(tree.byId.values + made), store.lends.roles()) { true }
        made.forEach { check(it.check(access, null)) }
        val keptTemplates = newTemplates.filter { it.check(access, null) == null }
        val known = stored + keptTemplates.associateBy { it.id }
        val kept = notes.filter { it.check(access, null, known[it.templateId]) == null }
        val keptIds = kept.map { it.id }.toSet() + made.map { it.id }
        val keptEvents = events.filter {
            it.check(access, now) == null && (it.subjectId in keptIds || it is Event.Void)
        }
        val first = contents.firstOrNull()?.let { decks[it.deck.id] }
        val planned = kept.map { it to known.getValue(it.templateId) }
        return Plan(first, made, keptTemplates, planned, keptEvents, decks, repeated, notes.size - kept.size, from)
    }

    /** Keeps what [plan] would add. */
    suspend fun add(plan: Plan): Added {
        store.transaction {
            plan.decks.forEach { store.decks.save(it) }
            plan.templates.forEach { store.templates.save(it) }
            plan.notes.forEach { (n, t) -> store.notes.save(n, t) }
            plan.events.forEach { store.events.save(it) }
        }
        store.clock()
        store.cards.refresh(plan.notes.map { it.first })
        return Added(plan.first, plan.notes.size, plan.there, plan.refused)
    }

    /** Adds [contents] under [parent] as [plan] would. */
    suspend fun add(contents: List<Contents>, parent: String?, fresh: Boolean = false): Added =
        add(plan(contents, parent, fresh))

    /** Where each card of [e] stands, from its events: progress without the answers that led there. */
    private fun snapshots(e: Contents.Entry, fsrs: app.rondo.core.Fsrs, now: Long): List<Event> {
        val history = History(e.events)
        return e.events.map { it.card }.filter { it > 0 }.distinct().mapNotNull { card ->
            history.card(e.note.id, card).state(fsrs).snapshot(e.note.id, card, store.me, now)
        }
    }

    /** Keeps [decks] and [notes] as changed (new ones too), each if its check passes: what was refused, by id. */
    suspend fun save(decks: List<Deck>, notes: List<Note>): Map<String, ErrorCode> {
        val refused = LinkedHashMap<String, ErrorCode>()
        for (deck in decks) {
            val d = deck.copy(v = store.hlc.next())
            val code = d.check(access(), store.decks.get(d.id))
            if (code == null) store.decks.save(d) else refused[d.id] = code
        }
        val access = access()
        val saved = ArrayList<Note>()
        for (note in notes) {
            val n = note.copy(v = store.hlc.next())
            val template = store.templates.get(n.templateId)
            val code = n.check(access, store.notes.get(n.id), template)
            if (code == null) {
                store.notes.save(n, template!!)
                saved += n
            } else {
                refused[n.id] = code
            }
        }
        store.clock()
        store.cards.refresh(saved)
        return refused
    }

    /**
     * The notes of [template] a note of it in [deck] could repeat: under the same top-level deck,
     * and with [key] (see [Template.key]), those that may have it.
     */
    suspend fun repeats(template: Template, deck: String, key: String? = null): List<Note> =
        store.notes.search(template.repeats(store.decks.tree(), deck, key), Int.MAX_VALUE)

    // Notes and note types -----------------------------------------------------------------------

    suspend fun note(id: String): Note? = store.notes.get(id)

    suspend fun saveNote(note: Note): Note {
        val template = store.templates.get(note.templateId)
        val stored = store.notes.get(note.id)
        val fresh = note.copy(v = store.clock(), ownerId = store.decks.get(note.deckId)?.ownerId)
        check(fresh.check(access(), stored, template))
        store.notes.save(fresh, template!!)
        store.cards.refresh(listOf(fresh))
        return fresh
    }

    suspend fun newNote(deck: String, template: String, fields: Map<String, String>, tags: Tags = Tags.NONE): Note =
        saveNote(Note(Uuid.generateV7().toString(), deck, template, fields, 0, tags = tags.text))

    /** Takes back [note], just added: gone without a trace if it wasn't synced yet, otherwise deleted. */
    suspend fun unadd(note: Note) {
        if (!store.notes.forget(note)) deleteNotes(listOf(note.id))
    }

    suspend fun deleteNotes(ids: List<String>) = ids.forEach { id ->
        store.notes.get(id)?.let { saveNote(it.copy(deletedAt = now())) }
    }

    suspend fun restoreNote(id: String) = store.notes.get(id)?.let { saveNote(it.copy(deletedAt = null)) }

    suspend fun moveNotes(ids: List<String>, deck: String) = ids.forEach { id ->
        store.notes.get(id)?.let { saveNote(it.copy(deckId = deck)) }
    }

    /** Adds [added] to the notes' tags and takes [removed] off; notes you can't change stay as they are. */
    suspend fun tag(ids: List<String>, added: List<String>, removed: List<String>) {
        val access = access()
        for (id in ids) {
            val n = store.notes.get(id)?.takeIf { access.canWrite(it.deckId) == null } ?: continue
            val tags = Tags(n.tags) + added - removed
            if (tags.text != n.tags) saveNote(n.copy(tags = tags.text))
        }
    }

    suspend fun saveTemplate(t: Template): Template {
        val fresh = t.with(v = store.clock(), ownerId = store.me)
        val stored = store.templates.get(t.id)?.takeIf { !it.builtin }
        check(fresh.check(access(), stored))
        if (fresh.deletedAt != null && store.templates.inUse(t.id)) throw Refused(ErrorCode.TEMPLATE_IN_USE)
        store.templates.save(fresh)
        return fresh
    }

    // Smart decks: the learner's own --------------------------------------------------------------

    suspend fun createSmartDeck(name: String, filter: Filter): SmartDeck {
        val after = store.smartDecks.all().lastOrNull()?.position
        return save(null, SmartDeck(name.trim(), filter, Position(after, null).key, store.clock(), store.me))
    }

    suspend fun updateSmartDeck(id: String, change: (SmartDeck) -> SmartDeck): SmartDeck = changing.withLock {
        val stored = store.smartDecks.get(id) ?: throw Refused(ErrorCode.NOT_FOUND)
        save(stored, change(stored).copy(v = store.clock()))
    }

    suspend fun deleteSmartDeck(id: String) = updateSmartDeck(id) { it.copy(deletedAt = now()) }

    private suspend fun save(stored: SmartDeck?, smart: SmartDeck): SmartDeck {
        check(smart.check(access(), stored))
        store.smartDecks.save(smart)
        return smart
    }

    // Your own study actions: events ------------------------------------------------------------

    /** Keeps [event], once its check passes, and folds its note's cards again. */
    suspend fun record(event: Event) {
        check(event.check(access(), now()))
        store.events.save(event)
        if (event !is Event.Unlock) store.notes.get(event.subjectId)?.let { store.cards.refresh(listOf(it)) }
    }

    private fun id() = Uuid.generateV7().toString()

    suspend fun suspendCards(note: String, on: Boolean) = store.cards.of(note).forEach {
        record(
            if (on) {
                Event.Suspend(
                    id(),
                    store.me,
                    note,
                    it.card,
                    now(),
                )
            } else {
                Event.Unsuspend(id(), store.me, note, it.card, now())
            },
        )
    }

    suspend fun flag(note: String, card: Int, color: Int) = record(Event.Flag(id(), store.me, note, card, now(), color))

    /** Marks every card of [note] (any flag colour counts), or takes the marks off. */
    suspend fun mark(note: String, on: Boolean) = store.cards.of(note).forEach { flag(note, it.card, if (on) 1 else 0) }

    suspend fun bury(note: String, card: Int) = record(Event.Bury(id(), store.me, note, card, now()))

    suspend fun reset(note: String) = store.cards.of(note).forEach {
        record(Event.Reset(id(), store.me, note, it.card, now()))
    }

    suspend fun unlock(deck: String) = record(Event.Unlock(id(), store.me, deck, now()))
}
