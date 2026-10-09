package app.rondo.client

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.rondo.core.Memory
import app.rondo.core.Tags
import app.rondo.core.Templates
import app.rondo.core.model.Filter
import app.rondo.core.notes
import kotlin.js.JsExport

/** A note in Browse. [state]: new, learning, review or suspended (its least advanced card). */
@JsExport
class NoteItem internal constructor(
    val id: String,
    val text: String,
    val deck: String,
    val template: String,
    val state: String,
    val due: Double?,
    val marked: Boolean,
    val canEdit: Boolean,
    val tags: Array<String>,
)

@JsExport
class BrowseState internal constructor(
    val notes: Array<NoteItem>,
    val more: Boolean,
    val text: String,
    val deckId: String?,
    val templateId: String?,
    val cardState: String?,
    val marked: Boolean,
    /** added, changed, due or alpha. */
    val sort: String,
    val decks: Array<Choice>,
    val templates: Array<Choice>,
    /** Tags picked: notes with any of them, or with a tag under one. */
    val tags: Array<String>,
    /** What "Study these" offers now: cards due (learning and review), and new ones. */
    val due: Int,
    val newCount: Int,
    /** The smart deck whose rules these filters are, while Browse changes them. */
    val smart: Choice?,
)

/** Browse's filters as a smart deck keeps them, empty ones left out. */
internal fun picked(
    text: String,
    deckId: String?,
    templateId: String?,
    tags: Array<String>,
    cardState: String?,
    marked: Boolean,
) = Filter(
    text = text.trim().ifEmpty { null },
    deckIds = listOfNotNull(deckId?.ifBlank { null }).ifEmpty { null },
    templateIds = listOfNotNull(templateId?.ifBlank { null }).ifEmpty { null },
    tags = tags.mapNotNull(Tags::clean).distinctBy { it.lowercase() }.ifEmpty { null },
    state = cardState?.takeIf { it in STATES },
    marked = marked.takeIf { it },
)

private val STATES = setOf("new", "learning", "review", "suspended")

/**
 * Finding notes with filter pickers, and changing many at once. The filters can be studied as
 * they are, kept as a smart deck, or, opened from a smart deck ([smartId]), become its rules.
 */
@JsExport
class BrowseScreen internal constructor(override val app: App, deckId: String?, private val smartId: String?) :
    Screen<BrowseState>() {
    private val store get() = app.rondo.store
    private val library get() = app.rondo.library
    private var filter = Filter(deckIds = listOfNotNull(deckId).ifEmpty { null })
    private var sort = "added"
    private var limit = 200

    override suspend fun load(): BrowseState {
        val day = Day.of(store, library.overview())
        val tree = day.tree
        val access = library.access()
        val templates = store.templates()
        val found = store.search(filter.notes(tree), limit + 1, sort)
        val notes = found.take(limit)
        val cards = notes.map { it.id }.chunked(500).flatMap { store.q.cardsOfNotes(it).awaitAsList() }
            .groupBy { it.note_id }
        val items = notes.map { n ->
            val c = cards[n.id].orEmpty()
            val live = c.filter { !it.suspended }
            val state = when {
                live.isEmpty() -> "suspended"
                live.any { it.state == Memory.NEW.toLong() } -> "new"
                live.any { it.state != Memory.REVIEW.toLong() } -> "learning"
                else -> "review"
            }
            val template = templates[n.templateId]
            NoteItem(
                id = n.id,
                text = label(n),
                deck = tree.path(n.deckId).joinToString(" › "),
                template = template?.name.orEmpty(),
                state = state,
                due = live.mapNotNull { it.due }.minOrNull()?.toDouble(),
                marked = c.any { it.flag > 0 },
                canEdit = access.canWrite(n.deckId) == null && template?.kind != Templates.ANKI,
                tags = Tags.of(n).list.toTypedArray(),
            )
        }
        val decks = tree.choices(tree.byId.values.filter { !tree.dead(it.id) })
        val types = templates.values.filter { it.deletedAt == null }.map { Choice(it.id, it.name) }
            .sortedBy { it.label.lowercase() }
        val offer = FilterScope.unlimited(store, filter).counts(day)
        val smart = smartId?.let { store.smartDeck(it) }?.takeIf { it.deletedAt == null }
        return BrowseState(
            items.toTypedArray(), found.size > limit, filter.text.orEmpty(), filter.deckIds?.firstOrNull(),
            filter.templateIds?.firstOrNull(), filter.state, filter.marked == true, sort, decks.toTypedArray(),
            types.toTypedArray(), filter.tags.orEmpty().toTypedArray(), offer.learning + offer.review, offer.new,
            smart?.let { Choice(it.id, it.name) },
        )
    }

    /**
     * Sets every filter at once; null or empty means any. [tags]: any of them; [cardState]: new,
     * learning, review or suspended; [sort]: added, changed, due or alpha.
     */
    fun filter(
        text: String,
        deckId: String?,
        templateId: String?,
        tags: Array<String>,
        cardState: String?,
        marked: Boolean,
        sort: String,
    ) = act {
        filter = picked(text, deckId, templateId, tags, cardState, marked)
        this.sort = sort.takeIf { it in SORTS } ?: "added"
        limit = 200
        reload()
    }

    fun more() = act {
        limit += 200
        reload()
    }

    /** Keeps the filters as a smart deck called [name]; [then] gets its id. */
    fun keep(name: String, then: (String) -> Unit) = act {
        if (name.isBlank()) return@act
        val smart = library.createSmartDeck(name, filter)
        app.rondo.syncSoon()
        then(smart.id)
    }

    /** Makes the filters the smart deck's rules; [then] runs once they are. */
    fun saveRules(then: () -> Unit) = act {
        library.updateSmartDeck(smartId ?: return@act) { it.copy(filter = filter) }
        app.rondo.syncSoon()
        then()
    }

    private fun each(ids: Array<String>, block: suspend (String) -> Unit) = act {
        ids.forEach { block(it) }
        app.rondo.syncSoon()
    }

    fun move(ids: Array<String>, deckId: String) = act {
        library.moveNotes(ids.toList(), deckId)
        app.rondo.syncSoon()
    }

    fun delete(ids: Array<String>) = act {
        library.deleteNotes(ids.toList())
        app.rondo.syncSoon()
    }

    fun suspend(ids: Array<String>, on: Boolean) = each(ids) { library.suspendCards(it, on) }

    fun reset(ids: Array<String>) = each(ids) { library.reset(it) }

    fun mark(ids: Array<String>, on: Boolean) = each(ids) { id ->
        store.q.cardsOf(id).awaitAsList().forEach { library.flag(id, it.card.toInt(), if (on) 1 else 0) }
    }

    /** Adds [added] to the notes' tags and takes [removed] off, on the notes you can change. */
    fun tag(ids: Array<String>, added: Array<String>, removed: Array<String>) = act {
        library.tag(ids.toList(), added.toList(), removed.toList())
        app.rondo.syncSoon()
    }

    private companion object {
        val SORTS = setOf("added", "changed", "due", "alpha")
    }
}

@JsExport
class TagsState internal constructor(val query: String, val tags: Array<String>)

/** Tags notes have, to pick from as you type: the top-level ones, then any with what's typed in them. */
@JsExport
class TagsScreen internal constructor(override val app: App) : Screen<TagsState>() {
    override val live get() = false
    private var query = ""

    override suspend fun load() = TagsState(query, app.rondo.store.tags(query, 50).toTypedArray())

    fun search(query: String) {
        this.query = query
        reload()
    }
}
