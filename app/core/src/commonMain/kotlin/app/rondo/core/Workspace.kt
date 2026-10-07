package app.rondo.core

import app.rondo.core.model.Deck
import app.rondo.core.model.Event
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
)

/** A filter as SQL both SQLite and Postgres run: `?` placeholders, ids marked for binding. */
class SqlFilter(val where: String, val args: List<Any>) {
    class Id(val value: String)
}

object Search {
    fun where(f: NoteFilter, cardStates: Boolean): SqlFilter {
        val parts = mutableListOf("n.deleted_at IS NULL")
        val args = mutableListOf<Any>()
        for (term in fold(f.text).split(' ').filter { it.isNotBlank() }) {
            parts += "n.search_text LIKE ? ESCAPE '\\'"
            args += "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        if (f.decks.isNotEmpty()) {
            parts += "n.deck_id IN (" + f.decks.joinToString(",") { "?" } + ")"
            args.addAll(f.decks.map { SqlFilter.Id(it) })
        }
        if (f.templates.isNotEmpty()) {
            parts += "n.template_id IN (" + f.templates.joinToString(",") { "?" } + ")"
            args.addAll(f.templates.map { SqlFilter.Id(it) })
        }
        if (cardStates && f.state != null) {
            parts += when (f.state) {
                "suspended" -> "EXISTS (SELECT 1 FROM card_state c WHERE c.note_id = n.id AND c.suspended = 1)"

                else -> {
                    val states = when (f.state) {
                        "new" -> "0"
                        "learning" -> "1, 3"
                        else -> "2"
                    }
                    "EXISTS (SELECT 1 FROM card_state c WHERE c.note_id = n.id AND c.suspended = 0 " +
                        "AND c.state IN ($states))"
                }
            }
        }
        if (cardStates && f.marked) parts += "EXISTS (SELECT 1 FROM card_state c WHERE c.note_id = n.id AND c.flag > 0)"
        return SqlFilter(parts.joinToString(" AND "), args)
    }
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
