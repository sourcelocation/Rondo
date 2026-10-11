package app.rondo.client

import app.rondo.core.AnkiTemplate
import app.rondo.core.Filter
import app.rondo.core.Memory
import app.rondo.core.NoteSort
import app.rondo.core.Tags
import io.ktor.http.formUrlEncode
import io.ktor.http.parametersOf
import io.ktor.http.parseQueryString
import io.ktor.util.toMap
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
class FilterState internal constructor(
    val text: String,
    val deckId: String?,
    val templateId: String?,
    /** Tags picked: notes with any of them, or with a tag under one. */
    val tags: Array<String>,
    /** new, learning, review or suspended. */
    val cardState: String?,
    val marked: Boolean,
    /** Decks to pick from: every live one, and the one picked when it's gone. */
    val decks: Array<Choice>,
    val templates: Array<Choice>,
    /** The filter as an address's search params, to open "Study these" with: see [App.studyThese]. */
    val address: String,
)

/**
 * A filter as its pickers show it: text, deck, note type, tags, card state and mark. Whoever makes
 * it says where it starts ([start]) and where each change is kept ([save]). While it's open, it is
 * the filter, as the note editor is the note being edited: it reads [start] once, so nothing
 * changes under the person's hands. A change made elsewhere meanwhile gives way to the next one
 * made here, as the newest whole row wins anywhere in sync.
 */
@JsExport
class FilterScreen internal constructor(
    override val app: App,
    private val start: suspend () -> Filter,
    private val save: suspend (Filter) -> Unit,
) : Screen<FilterState>() {
    private val library get() = app.rondo.library
    private var filter: Filter? = null

    /** The filter now. */
    internal suspend fun value(): Filter = filter ?: start().also { filter = it }

    override suspend fun load(): FilterState {
        val f = value()
        val tree = library.tree()
        val live = tree.byId.values.filter { !tree.dead(it.id) }
        val deckId = f.deckIds?.firstOrNull()
        val gone = deckId?.takeIf { tree.dead(it) }?.let { Choice(it, strings.ruleGoneDeck) }
        val types = library.templates().values.filter { it.deletedAt == null }.map { Choice(it.id, it.name) }
            .sortedBy { it.label.lowercase() }
        return FilterState(
            f.text.orEmpty(), deckId, f.templateIds?.firstOrNull(), f.tags.orEmpty().toTypedArray(), f.state,
            f.marked == true, (listOfNotNull(gone) + choices(tree, live)).toTypedArray(), types.toTypedArray(),
            parametersOf(f.params).formUrlEncode(),
        )
    }

    private fun change(change: (Filter) -> Filter) = act { set(change(value())) }

    /** Makes the filter [f], when it's another. */
    internal suspend fun set(f: Filter) {
        if (f == value()) return
        filter = f
        save(f)
        reload()
    }

    fun setText(text: String) = change { it.withText(text) }

    /** Notes in [deckId] and its sub-decks; null: every deck. */
    fun setDeck(deckId: String?) = change { it.withDeck(deckId) }

    fun setTemplate(templateId: String?) = change { it.withTemplate(templateId) }

    /** Notes with any of [tags], or a tag under one; none: any notes. */
    fun setTags(tags: Array<String>) = change { it.withTags(tags.toList()) }

    /** new, learning, review or suspended; null: any. */
    fun setCardState(cardState: String?) = change { it.withState(cardState) }

    fun setMarked(marked: Boolean) = change { it.withMarked(marked) }
}

@JsExport
class BrowseState internal constructor(
    val notes: Array<NoteItem>,
    val more: Boolean,
    val sort: NoteSort,
    /** The orders to choose from. */
    val sorts: Array<NoteSort>,
    /** Decks notes can move to. */
    val decks: Array<Choice>,
    /** What "Study these" offers now: cards due (learning and review), and new ones. */
    val due: Int,
    val newCount: Int,
    /** The filter and the sort as an address's search params: see [App.browse]. */
    val address: String,
)

/**
 * The notes a filter finds, in the order chosen, and changing many at once. Browse is this with its
 * filter kept in the address; a smart deck's notes are this with its rules as the filter.
 */
@JsExport
class BrowseScreen internal constructor(
    override val app: App,
    start: suspend () -> Filter,
    private var sort: NoteSort,
    save: suspend (Filter) -> Unit,
) : Screen<BrowseState>() {
    private val library get() = app.rondo.library
    private var limit = 200

    /** Opened on an [address] (see [BrowseState.address]); its filter is kept there alone. */
    internal constructor(app: App, address: String) :
        this(app, { Filter(parseQueryString(address.removePrefix("?")).toMap()) }, sortIn(address), {})

    /** What it finds, as pickers. */
    val filter: FilterScreen = part(
        FilterScreen(app, start) {
            save(it)
            limit = 200
            reload()
        },
    )

    override suspend fun load(): BrowseState {
        val study = app.rondo.study
        val day = study.day(library.overview())
        val tree = day.tree
        val access = library.access()
        val templates = library.templates()
        val f = filter.value()
        val found = library.search(f.notes(tree), limit + 1, sort)
        val notes = found.take(limit)
        val cards = library.cards(notes.map { it.id }).groupBy { it.noteId }
        val items = notes.map { n ->
            val c = cards[n.id].orEmpty().map { it.state }
            val live = c.filter { !it.suspended }
            val state = when {
                live.isEmpty() -> "suspended"
                live.any { it.memory.state == Memory.NEW } -> "new"
                live.any { it.memory.state != Memory.REVIEW } -> "learning"
                else -> "review"
            }
            val template = templates[n.templateId]
            NoteItem(
                id = n.id,
                text = template?.label(n) ?: "…",
                deck = tree.path(n.deckId).joinToString(" › "),
                template = template?.name.orEmpty(),
                state = state,
                due = live.mapNotNull { it.memory.due }.minOrNull()?.toDouble(),
                marked = c.any { it.flag > 0 },
                canEdit = access.canWrite(n.deckId) == null && template !is AnkiTemplate,
                tags = Tags(n.tags).list.toTypedArray(),
            )
        }
        val decks = choices(tree, tree.byId.values.filter { !tree.dead(it.id) })
        val offer = study.counts(f, day)
        val address =
            f.params + listOfNotNull(("sort" to listOf(sort.name.lowercase())).takeIf { sort != NoteSort.ADDED })
        return BrowseState(
            items.toTypedArray(),
            found.size > limit,
            sort,
            SORTS.toTypedArray(),
            decks.toTypedArray(),
            offer.learning + offer.review,
            offer.new,
            parametersOf(address).formUrlEncode(),
        )
    }

    /** One of [BrowseState.sorts]. */
    fun sortBy(sort: NoteSort) = act {
        this.sort = sort.takeIf { it in SORTS } ?: NoteSort.ADDED
        limit = 200
        reload()
    }

    /** Shows what [address] says (see [BrowseState.address]) when it says something else: after Back, or a link. */
    fun open(address: String) = act {
        val sort = sortIn(address)
        if (sort != this.sort) sortBy(sort)
        filter.set(Filter(parseQueryString(address.removePrefix("?")).toMap()))
    }

    fun more() = act {
        limit += 200
        reload()
    }

    /** Keeps the filter as a smart deck called [name]; [then] gets its id. */
    fun keep(name: String, then: (String) -> Unit) = act {
        if (name.isBlank()) return@act
        val smart = library.createSmartDeck(name, filter.value())
        app.rondo.syncSoon()
        then(smart.id)
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

    fun mark(ids: Array<String>, on: Boolean) = each(ids) { library.mark(it, on) }

    /** Adds [added] to the notes' tags and takes [removed] off, on the notes you can change. */
    fun tag(ids: Array<String>, added: Array<String>, removed: Array<String>) = act {
        library.tag(ids.toList(), added.toList(), removed.toList())
        app.rondo.syncSoon()
    }

    private companion object {
        val SORTS = listOf(NoteSort.ADDED, NoteSort.CHANGED, NoteSort.DUE, NoteSort.ALPHA)

        /** The sort [address] asks for; none: the newest first. */
        fun sortIn(address: String): NoteSort {
            val asked = parseQueryString(address.removePrefix("?"))["sort"]
            return SORTS.firstOrNull { it.name.equals(asked, ignoreCase = true) } ?: NoteSort.ADDED
        }
    }
}

@JsExport
class TagsState internal constructor(val query: String, val tags: Array<String>)

/** Tags notes have, to pick from as you type: the top-level ones, then any with what's typed in them. */
@JsExport
class TagsScreen internal constructor(override val app: App) : Screen<TagsState>() {
    override val live get() = false
    private var query = ""

    override suspend fun load() = TagsState(query, app.rondo.library.tags(query, 50).toTypedArray())

    fun search(query: String) {
        this.query = query
        reload()
    }
}
