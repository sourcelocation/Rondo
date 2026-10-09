package app.rondo.core

import app.rondo.core.model.Deck
import app.rondo.core.model.Event
import app.rondo.core.model.Filter
import app.rondo.core.model.Note
import app.rondo.core.model.Rejection
import app.rondo.core.model.Rows
import app.rondo.core.model.Template

/** What Browse and the MCP tools look notes up by. Empty fields match everything. */
data class NoteFilter(
    val text: String = "",
    /** Deck ids to look in (the caller expands sub-decks); empty: every deck. */
    val decks: List<String> = emptyList(),
    val templates: List<String> = emptyList(),
    /** new, learning, review or suspended; needs per-card state, so devices only. */
    val state: String? = null,
    /** Only marked notes (any flag colour counts). */
    val marked: Boolean = false,
    /** Notes with any of these tags, or a tag under one of them. */
    val tags: List<String> = emptyList(),
)

/** A filter as SQL both SQLite and Postgres run: `?` placeholders, ids marked for binding. */
class SqlFilter(val where: String, val args: List<Any>) {
    class Id(val value: String)
}

object Search {
    /** No deck has this id: decks that are all gone find nothing, where none at all would find everything. */
    const val NOWHERE = "00000000-0000-0000-0000-000000000000"

    /** [f] over notes (`notes n`); a note matches [NoteFilter.state] and the mark by any of its cards. */
    fun where(f: NoteFilter, cardStates: Boolean): SqlFilter {
        val (parts, args) = notes(f)
        if (cardStates && f.state != null) {
            parts += "EXISTS (SELECT 1 FROM card_state c WHERE c.note_id = n.id AND ${state(f.state)})"
        }
        if (cardStates && f.marked) parts += "EXISTS (SELECT 1 FROM card_state c WHERE c.note_id = n.id AND c.flag > 0)"
        return SqlFilter(parts.joinToString(" AND "), args)
    }

    /**
     * [f] over cards, as studying takes them: `card_state c` joined to its note `n`, the state and
     * the mark each card's own. Devices only.
     */
    fun cards(f: NoteFilter): SqlFilter {
        val (parts, args) = notes(f)
        return SqlFilter((parts + card(f)).joinToString(" AND "), args)
    }

    /** What [f] asks of each card itself, its state and mark, over `card_state c`; "1" when nothing. */
    fun card(f: NoteFilter): String =
        listOfNotNull(f.state?.let(::state), "c.flag > 0".takeIf { f.marked }).joinToString(" AND ").ifEmpty { "1" }

    /** What a note itself says: its text, deck, note type and tags. */
    private fun notes(f: NoteFilter): Pair<MutableList<String>, MutableList<Any>> {
        val parts = mutableListOf("n.deleted_at IS NULL")
        val args = mutableListOf<Any>()
        for (term in fold(f.text).split(' ').filter { it.isNotBlank() }) {
            parts += "n.search_text LIKE ? ESCAPE '\\'"
            args += "%" + escape(term) + "%"
        }
        if (f.decks.isNotEmpty()) {
            parts += "n.deck_id IN (" + f.decks.joinToString(",") { "?" } + ")"
            args.addAll(f.decks.map { SqlFilter.Id(it) })
        }
        if (f.templates.isNotEmpty()) {
            parts += "n.template_id IN (" + f.templates.joinToString(",") { "?" } + ")"
            args.addAll(f.templates.map { SqlFilter.Id(it) })
        }
        val tags = f.tags.mapNotNull(Tags::clean)
        if (tags.isNotEmpty()) {
            // Tags are stored space-separated: a tag is " tag " in the padded text, the ones under it " tag::…".
            val padded = "lower(' ' || n.tags || ' ')"
            parts += tags.joinToString(" OR ", "(", ")") {
                "$padded LIKE lower(?) ESCAPE '\\' OR $padded LIKE lower(?) ESCAPE '\\'"
            }
            for (t in tags) args.addAll(listOf("% ${escape(t)} %", "% ${escape(t)}::%"))
        }
        return parts to args
    }

    private fun state(state: String): String = when (state) {
        "suspended" -> "c.suspended = 1"
        "new" -> "c.suspended = 0 AND c.state = 0"
        "learning" -> "c.suspended = 0 AND c.state IN (1, 3)"
        else -> "c.suspended = 0 AND c.state = 2"
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}

/**
 * What a filter as picked finds in [tree]: its decks with every live deck under them (none picked:
 * every live deck), so notes in deleted decks never come along.
 */
fun Filter.notes(tree: Tree): NoteFilter {
    val picked = deckIds.orEmpty().takeIf { it.isNotEmpty() } ?: tree.roots.map { it.id }
    val decks = picked.filter { !tree.dead(it) }.flatMap { tree.subtree(it) }.map { it.id }.distinct()
    return NoteFilter(
        text = text.orEmpty(),
        decks = decks.ifEmpty { listOf(Search.NOWHERE) },
        templates = templateIds.orEmpty(),
        state = state,
        marked = marked == true,
        tags = tags.orEmpty(),
    )
}

/** A deck as an agent or a list sees it. */
data class DeckView(val deck: Deck, val path: List<String>, val role: Role, val notes: Int)

/**
 * One account's data, wherever it lives: a device's SQLite (the command-line MCP client) or the
 * server's Postgres (hosted MCP). [commit] always goes through [Rules].
 */
interface Workspace {
    val actor: String

    suspend fun decks(): List<DeckView>

    suspend fun templates(): List<Template>

    suspend fun notes(filter: NoteFilter, limit: Int): List<Note>

    suspend fun notes(ids: List<String>): List<Note>

    suspend fun events(noteIds: List<String>): List<Event>

    suspend fun settingsTimezone(): String?

    suspend fun commit(rows: Rows): List<Rejection>

    /** A clock value newer than anything this workspace has seen. */
    fun clock(): Long
}

/** Copying decks into your own: new ids, you as owner, progress carried over as snapshots. */
object Copy {
    fun decks(
        roots: List<String>,
        tree: Tree,
        notes: List<Note>,
        templates: Map<String, Template>,
        states: Map<Pair<String, Int>, CardState>,
        actor: String,
        parent: String?,
        position: String,
        clock: () -> Long,
    ): Rows {
        val sources = roots.flatMap { tree.subtree(it) }
        val deckIds = sources.associate { it.id to Ids.new() }
        val decks = sources.map { d ->
            val root = d.id in roots
            d.copy(
                id = deckIds.getValue(d.id),
                ownerId = actor,
                v = clock(),
                deletedAt = null,
                parentId = if (root) parent else deckIds[d.parentId],
                position = if (root) position else d.position,
            )
        }
        val byNote = states.entries.groupBy { it.key.first }
        val templateIds = HashMap<String, String>()
        val used = notes.map { it.templateId }.distinct().filterNot(Templates::isBuiltin).mapNotNull { templates[it] }
        val copiedTemplates = used.map { t ->
            t.copy(id = Ids.new().also { templateIds[t.id] = it }, ownerId = actor, v = clock(), deletedAt = null)
        }
        val events = ArrayList<Event>()
        val copiedNotes = notes.filter { it.deckId in deckIds && it.deletedAt == null }.map { n ->
            val id = Ids.new()
            for ((key, s) in byNote[n.id].orEmpty()) {
                if (s.memory.state == Memory.NEW) continue
                val m = s.memory
                events += Event(
                    id = Ids.new(), userId = actor, subjectId = id, card = key.second, kind = Learning.SNAPSHOT,
                    at = m.last ?: nowMillis(), value = m.state, due = m.due, stability = m.stability,
                    difficulty = m.difficulty, reps = m.reps, lapses = m.lapses,
                )
            }
            n.copy(
                id = id,
                ownerId = actor,
                deckId = deckIds.getValue(n.deckId),
                templateId = templateIds[n.templateId] ?: n.templateId,
                v = clock(),
            )
        }
        return Rows(decks, copiedTemplates, copiedNotes, events)
    }
}
