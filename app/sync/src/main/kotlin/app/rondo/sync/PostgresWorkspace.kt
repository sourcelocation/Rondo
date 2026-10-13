package app.rondo.sync

import app.rondo.core.Access
import app.rondo.core.Deck
import app.rondo.core.DeckView
import app.rondo.core.ErrorCode
import app.rondo.core.Event
import app.rondo.core.Hlc
import app.rondo.core.Note
import app.rondo.core.NoteFilter
import app.rondo.core.Role
import app.rondo.core.Template
import app.rondo.core.Tree
import app.rondo.core.Workspace
import app.rondo.core.record.DeckRecord
import app.rondo.core.record.EventRecord
import app.rondo.core.record.NoteRecord
import app.rondo.core.record.Record
import app.rondo.core.record.TemplateRecord
import app.rondo.core.sql.NoteQuery
import app.rondo.core.sql.SqlFilter
import java.util.UUID

/** Hosted MCP's view: the actor's own decks and the ones lent to them, read from Postgres. */
class PostgresWorkspace(override val actor: String, private val postgres: Postgres, private val sync: SyncService) : Workspace {
    private val hlc = Hlc(Hlc.DEVICE_MASK)

    /** The actor's decks and the ones lent to them, with everything under them; and the lends. */
    private fun visible(): Pair<Tree, Map<String, Int>> = postgres.transaction { sql ->
        val lends = sql.query("SELECT deck_id, role FROM shares WHERE user_id = ?::uuid", actor) {
            it.getString(1) to it.getInt(2)
        }.toMap()
        val decks = sql.query(
            "WITH RECURSIVE sub AS (SELECT id FROM decks WHERE owner_id = ?::uuid OR id = ANY(?::uuid[]) " +
                "UNION SELECT d.id FROM decks d JOIN sub ON d.parent_id = sub.id) " +
                "SELECT (to_jsonb(d) - 'seq')::text FROM decks d WHERE d.id IN (SELECT id FROM sub) AND d.deleted_at IS NULL",
            actor,
            lends.keys,
        ) { Record.json.decodeFromString(DeckRecord.serializer(), it.getString(1)).deck() }
        Tree(decks) to lends
    }

    override suspend fun decks(): List<DeckView> {
        val (tree, lends) = visible()
        val access = Access(actor, tree, lends) { true }
        val sql = "SELECT deck_id, count(*) FROM notes " +
            "WHERE deck_id = ANY(?::uuid[]) AND deleted_at IS NULL GROUP BY deck_id"
        val counts = postgres.transaction { it.query(sql, tree.byId.keys) { r -> r.getString(1) to r.getInt(2) }.toMap() }
        val views = tree.byId.values.map { DeckView(it, tree.path(it.id), access.role(it.id), counts[it.id] ?: 0) }
        return views.filter { it.role != Role.NONE }
    }

    override suspend fun templates(): List<Template> {
        val decks = visible().first.byId.keys
        return Template.builtins.values + postgres.transaction { sql ->
            sql.query(
                "SELECT (to_jsonb(t) - 'seq')::text FROM templates t WHERE t.deleted_at IS NULL AND " +
                    "(t.owner_id = ?::uuid OR t.id IN (SELECT template_id FROM notes WHERE deck_id = ANY(?::uuid[])))",
                actor,
                decks,
            ) { Record.json.decodeFromString(TemplateRecord.serializer(), it.getString(1)).template() }.filterNotNull()
        }
    }

    override suspend fun notes(filter: NoteFilter, limit: Int): List<Note> {
        val decks = visible().first.byId.keys
        // Only decks the actor sees; asking for none means all of them.
        val asked = filter.decks.filter { it in decks }
        if (filter.decks.isNotEmpty() && asked.isEmpty()) return emptyList()
        val where = NoteQuery(filter.copy(decks = asked.ifEmpty { decks.toList() })).notes(cardStates = false)
        val args = where.args.map { if (it is SqlFilter.Id) UUID.fromString(it.value) else it } + limit
        val sql = "SELECT (to_jsonb(n) - 'seq' - 'search_text')::text FROM notes n " +
            "WHERE ${where.where} ORDER BY n.id LIMIT ?"
        return postgres.transaction { it.query(sql, *args.toTypedArray(), row = ::note) }
    }

    override suspend fun notes(ids: List<String>): List<Note> {
        val decks = visible().first.byId.keys
        val sql = "SELECT (to_jsonb(n) - 'seq' - 'search_text')::text FROM notes n WHERE n.id = ANY(?::uuid[])"
        return postgres.transaction { it.query(sql, ids, row = ::note) }.filter { it.deckId in decks }
    }

    private fun note(r: java.sql.ResultSet): Note = Record.json.decodeFromString(NoteRecord.serializer(), r.getString(1)).note()

    /** The events about [noteIds], with the voids taking any of them back. */
    override suspend fun events(noteIds: List<String>): List<Event> = postgres.transaction { sql ->
        fun about(subjects: Collection<String>) = sql.query(
            "SELECT (to_jsonb(e) - 'seq')::text FROM events e WHERE e.user_id = ?::uuid AND e.subject_id = ANY(?::uuid[])",
            actor,
            subjects,
        ) { Record.json.decodeFromString(EventRecord.serializer(), it.getString(1)).event() }.filterNotNull()
        val own = about(noteIds)
        own + about(own.map { it.id })
    }

    override suspend fun settingsTimezone(): String? = postgres.transaction { sql ->
        sql.query("SELECT timezone FROM settings WHERE user_id = ?::uuid", actor) { it.getString(1) }.firstOrNull()
    }

    override suspend fun save(decks: List<Deck>, notes: List<Note>): Map<String, ErrorCode> = postgres.transaction { sql ->
        val pushed = sync.push(sql, actor, decks.map(::DeckRecord) + notes.map(::NoteRecord))
        pushed.rejected.associate { r -> r.id to (ErrorCode.entries.firstOrNull { it.value == r.code } ?: ErrorCode.INVALID) }
    }

    override fun clock(): Long = hlc.next()
}
