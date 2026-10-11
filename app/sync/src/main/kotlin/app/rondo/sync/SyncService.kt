package app.rondo.sync

import app.rondo.core.Access
import app.rondo.core.Deck
import app.rondo.core.ErrorCode
import app.rondo.core.Hlc
import app.rondo.core.Role
import app.rondo.core.Template
import app.rondo.core.Tree
import app.rondo.core.record.DeckRecord
import app.rondo.core.record.Record
import app.rondo.core.sync.Fetch
import app.rondo.core.sync.Fetched
import app.rondo.core.sync.Lend
import app.rondo.core.sync.Page
import app.rondo.core.sync.Pull
import app.rondo.core.sync.Pulled
import app.rondo.core.sync.Push
import app.rondo.core.sync.Pushed
import app.rondo.core.sync.Stream
import java.security.MessageDigest
import java.sql.ResultSet
import java.sql.SQLException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A request refused: its HTTP [status] and [code]. */
class Problem(val status: Int, val code: ErrorCode, message: String) : Exception(message)

/**
 * Pull, push and fetch over Postgres, each thing checked by its own rules. Every kind is its
 * [Table]'s: this loops over them, in the order the server applies what a push holds.
 */
class SyncService(private val postgres: Postgres, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val decks = DeckTable()
    private val templates = TemplateTable()
    private val notes = NoteTable(decks, templates)
    private val tables: List<Table> = listOf(decks, templates, notes, EventTable(), SettingsTable(), SmartDeckTable())
    private val limit = 1000

    fun pull(actor: String, pull: Pull): Pulled = postgres.transaction(repeatable = true) { sql ->
        val lends = lends(sql, actor)
        val streams = pull.streams.associateBy { it.key }
        val keys = listOf(ME) + lends.map { it.deckId }
        Pulled(
            lends,
            keys.map { key ->
                val stream = streams[key] ?: Stream(key, 0)
                if (key == ME) own(sql, actor, stream) else lent(sql, lends.first { it.deckId == key }, stream)
            },
        )
    }

    private fun lends(sql: Sql, actor: String): List<Lend> = sql.query(
        "SELECT s.deck_id, s.role, d.owner_id, u.name FROM shares s LEFT JOIN decks d ON d.id = s.deck_id " +
            "LEFT JOIN users u ON u.id = d.owner_id WHERE s.user_id = ?::uuid ORDER BY s.created_at",
        actor,
    ) { Lend(it.getString(1), it.getInt(2), it.getString(3), it.getString(4)) }

    /** An owner's change counter: the latest seq, and the one up to which deleted rows were purged. */
    private fun counter(sql: Sql, owner: String): Pair<Long, Long> =
        sql.query("SELECT seq, purged_seq FROM counters WHERE owner_id = ?::uuid", owner) {
            it.getLong(1) to
                it.getLong(2)
        }
            .firstOrNull() ?: (0L to 0L)

    /** Nothing new on the stream since [s]. */
    private fun unchanged(s: Stream) =
        Page(s.key, emptyList(), s.cursor, more = false, resync = false, fingerprint = s.fingerprint)

    /** The device starts the stream over: rows it hasn't seen were purged meanwhile. */
    private fun resync(s: Stream) = Page(s.key, emptyList(), 0, more = false, resync = true)

    /** A row of a stream: its seq, and itself as its table's record. */
    private fun stamped(r: ResultSet): Pair<Long, Record> =
        r.getLong(1) to tables.first { it.name == r.getString(2) }.record(r.getString(3))

    private fun own(sql: Sql, actor: String, s: Stream): Page {
        val (seq, purged) = counter(sql, actor)
        if (s.cursor in 1 until purged) return resync(s)
        if (seq <= s.cursor) return unchanged(s)
        val ids = sql.query("SELECT id FROM decks WHERE owner_id = ?::uuid", actor) { it.getString(1) }
        val union = tables.joinToString(" UNION ALL ") { t ->
            "SELECT seq, '${t.name}', (to_jsonb(x) - 'seq' - 'search_text')::text FROM ${t.name} x " +
                "WHERE ${t.owner} = ?::uuid AND seq > ?"
        }
        val args = tables.flatMap { listOf<Any>(actor, s.cursor) } + (limit + 1)
        return page(s, sql.query("$union ORDER BY 1 LIMIT ?", *args.toTypedArray(), row = ::stamped), seq, ids)
    }

    private fun lent(sql: Sql, lend: Lend, s: Stream): Page {
        val counter = lend.ownerId?.let { counter(sql, it) }
        if (counter != null) {
            if (s.cursor in 1 until counter.second) return resync(s)
            if (counter.first <= s.cursor) return unchanged(s)
        }
        val ids = decks.subtree(sql, listOf(lend.deckId))
        val parts = tables.mapNotNull { it.lent }
        val args = parts.flatMap { listOf<Any>(ids, s.cursor) } + (limit + 1)
        val found = sql.query(
            parts.joinToString(" UNION ALL ") + " ORDER BY 1 LIMIT ?",
            *args.toTypedArray(),
            row = ::stamped,
        )
        return page(s, found, counter?.first ?: (found.maxOfOrNull { it.first } ?: s.cursor), ids)
    }

    private fun page(s: Stream, found: List<Pair<Long, Record>>, head: Long, ids: List<String>): Page {
        val more = found.size > limit
        val records = found.take(limit)
        val fingerprint = fingerprint(ids)
        return Page(
            s.key,
            records.map { it.second },
            if (more) records.last().first else maxOf(head, s.cursor),
            more,
            resync = false,
            fingerprint = fingerprint,
            deckIds = ids.takeIf { fingerprint != s.fingerprint },
        )
    }

    /** A stream's decks, in a few characters: when it changes, the device is sent the whole list to compare. */
    private fun fingerprint(ids: List<String>): String = MessageDigest.getInstance("SHA-256")
        .digest(ids.sorted().joinToString(",").encodeToByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    private fun access(sql: Sql, actor: String, decks: Collection<Deck>): Access {
        val lends = sql.query("SELECT deck_id, role FROM shares WHERE user_id = ?::uuid", actor) {
            it.getString(1) to
                it.getInt(2)
        }.toMap()
        val owners = decks.mapNotNull { it.ownerId }.toSet()
        val pro = if (owners.isEmpty()) {
            emptySet()
        } else {
            sql.query("SELECT id FROM users WHERE id = ANY(?::uuid[]) AND pro_until > now()", owners) {
                it.getString(1)
            }.toSet()
        }
        return Access(actor, Tree(decks), lends, pro::contains)
    }

    fun push(actor: String, push: Push): Pushed = postgres.transaction { sql -> push(sql, actor, push.records) }

    /** Checks and keeps [records] for [actor]; also the write path of hosted MCP. */
    fun push(sql: Sql, actor: String, records: List<Record>): Pushed {
        val now = clock()
        val hlc = Hlc(0) { now }
        if (records.mapNotNull {
                it.v
            }.any(hlc::ahead)
        ) {
            throw Problem(409, ErrorCode.CLOCK_AHEAD, "This device's clock is ahead.")
        }
        val batch = Batch(sql, actor, now)
        tables.forEach { it.load(records, batch) }
        batch.access = access(sql, actor, batch.decks.values)
        for (r in records) tables.forEach { it.accept(r, batch) }

        // Each owner's writes get the next seqs of their counter, in order.
        val byOwner = batch.writes.groupBy { it.owner }
        for (owner in (byOwner.keys.filterNotNull() + batch.touched).toSortedSet()) {
            val count = byOwner[owner].orEmpty().size.toLong().coerceAtLeast(1)
            val end = try {
                sql.exec("INSERT INTO counters (owner_id) VALUES (?::uuid) ON CONFLICT DO NOTHING", owner)
                sql.query("UPDATE counters SET seq = seq + ? WHERE owner_id = ?::uuid RETURNING seq", count, owner) {
                    it.getLong(1)
                }.single()
            } catch (e: SQLException) {
                throw Problem(401, ErrorCode.UNAUTHORIZED, "The account is not set up yet.")
            }
            byOwner[owner].orEmpty().forEachIndexed { i, w ->
                postgres.upsert(sql, w.table, JsonObject(w.row + ("seq" to JsonPrimitive(end - count + 1 + i))))
            }
        }
        return Pushed(batch.writes.size, batch.rejected)
    }

    /** Decks the actor may read, whole: each with its notes, and the note types those use. */
    fun fetch(actor: String, fetch: Fetch): Fetched = postgres.transaction { sql ->
        val access = access(sql, actor, decks.ancestors(sql, fetch.decks))
        val readable = fetch.decks.filter { access.role(it) != Role.NONE }
        val found = notes.inDecks(sql, readable)
        val wanted = (fetch.templates + found.map { it.templateId }).filter { it !in Template.builtins }
        Fetched(readable.mapNotNull { access.tree[it]?.let(::DeckRecord) } + templates.byId(sql, wanted) + found)
    }

    private companion object {
        /** The account's own stream: everything it owns, its settings and its events. */
        const val ME = "me"
    }
}
