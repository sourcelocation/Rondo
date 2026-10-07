package app.rondo.sync

import app.rondo.core.Access
import app.rondo.core.DeckView
import app.rondo.core.Hlc
import app.rondo.core.NoteFilter
import app.rondo.core.Role
import app.rondo.core.Search
import app.rondo.core.SqlFilter
import app.rondo.core.Templates
import app.rondo.core.Tree
import app.rondo.core.Workspace
import app.rondo.core.model.Deck
import app.rondo.core.model.Event
import app.rondo.core.model.Note
import app.rondo.core.model.Rejection
import app.rondo.core.model.Rows
import app.rondo.core.model.Template
import java.util.UUID

/** Hosted MCP's view: the actor's own decks and the ones lent to them, read from Postgres. */
class PostgresWorkspace(override val actor: String, private val db: Db, private val sync: Sync) : Workspace {
    private val hlc = Hlc(Hlc.DEVICE_MASK)

    /** The actor's decks and the ones lent to them, with everything under them; and the lends. */
    private fun visible(): Pair<Tree, Map<String, Int>> = db.transaction { c ->
        val lends = c.query("SELECT deck_id, role FROM shares WHERE user_id = ?::uuid", actor) {
            it.getString(1) to it.getInt(2)
        }.toMap()
        val sql = "WITH RECURSIVE sub AS (SELECT id FROM decks WHERE owner_id = ?::uuid OR id = ANY(?::uuid[]) " +
            "UNION SELECT d.id FROM decks d JOIN sub ON d.parent_id = sub.id) " +
            "SELECT (to_jsonb(d) - 'seq')::text FROM decks d " +
            "WHERE d.id IN (SELECT id FROM sub) AND d.deleted_at IS NULL"
        Tree(c.rows<Deck>(sql, actor, lends.keys)) to lends
    }

    override suspend fun decks(): List<DeckView> {
        val (tree, lends) = visible()
        val access = Access(actor, tree, lends) { true }
        val sql = "SELECT deck_id, count(*) FROM notes WHERE deck_id = ANY(?::uuid[]) AND deleted_at IS NULL " +
            "GROUP BY deck_id"
        val counts = db.transaction { c -> c.query(sql, tree.byId.keys) { it.getString(1) to it.getInt(2) }.toMap() }
        val views = tree.byId.values.map { DeckView(it, tree.path(it.id), access.role(it.id), counts[it.id] ?: 0) }
        return views.filter { it.role != Role.NONE }
    }

    override suspend fun templates(): List<Template> {
        val decks = visible().first.byId.keys
        return Templates.builtins + db.transaction { c ->
            c.rows<Template>(
                "SELECT (to_jsonb(t) - 'seq')::text FROM templates t WHERE t.deleted_at IS NULL AND " +
                    "(t.owner_id = ?::uuid OR t.id IN (SELECT template_id FROM notes WHERE deck_id = ANY(?::uuid[])))",
                actor,
                decks,
            )
        }
    }

    override suspend fun notes(filter: NoteFilter, limit: Int): List<Note> {
        val decks = visible().first.byId.keys
        // Only decks the actor sees; asking for none means all of them.
        val asked = filter.decks.filter { it in decks }
        if (filter.decks.isNotEmpty() && asked.isEmpty()) return emptyList()
        val where = Search.where(filter.copy(decks = asked.ifEmpty { decks.toList() }), cardStates = false)
        val args = where.args.map { if (it is SqlFilter.Id) UUID.fromString(it.value) else it } + limit
        val sql = "SELECT (to_jsonb(n) - 'seq' - 'search_text')::text FROM notes n " +
            "WHERE ${where.where} ORDER BY n.id LIMIT ?"
        return db.transaction { c -> c.rows(sql, *args.toTypedArray()) }
    }

    override suspend fun notes(ids: List<String>): List<Note> {
        val decks = visible().first.byId.keys
        val sql = "SELECT (to_jsonb(n) - 'seq' - 'search_text')::text FROM notes n WHERE n.id = ANY(?::uuid[])"
        return db.transaction { c -> c.rows<Note>(sql, ids) }.filter { it.deckId in decks }
    }

    override suspend fun events(noteIds: List<String>): List<Event> = db.transaction { c ->
        c.rows(
            "SELECT (to_jsonb(e) - 'seq')::text FROM events e " +
                "WHERE e.user_id = ?::uuid AND e.subject_id = ANY(?::uuid[])",
            actor,
            noteIds,
        )
    }

    override suspend fun settingsTimezone(): String? = db.transaction { c ->
        c.query("SELECT timezone FROM settings WHERE user_id = ?::uuid", actor) { it.getString(1) }.firstOrNull()
    }

    override suspend fun commit(rows: Rows): List<Rejection> = db.transaction { c ->
        sync.push(c, actor, rows).rejected
    }

    override fun clock(): Long = hlc.next()
}
