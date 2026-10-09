package app.rondo.sync

import app.rondo.core.Access
import app.rondo.core.Fingerprint
import app.rondo.core.Role
import app.rondo.core.Rules
import app.rondo.core.Templates
import app.rondo.core.Tree
import app.rondo.core.model.Deck
import app.rondo.core.model.ErrorCode
import app.rondo.core.model.Event
import app.rondo.core.model.FetchRequest
import app.rondo.core.model.Lend
import app.rondo.core.model.Note
import app.rondo.core.model.PullRequest
import app.rondo.core.model.PullResponse
import app.rondo.core.model.PushRequest
import app.rondo.core.model.PushResponse
import app.rondo.core.model.Rejection
import app.rondo.core.model.Rows
import app.rondo.core.model.Settings
import app.rondo.core.model.SmartDeck
import app.rondo.core.model.StreamPage
import app.rondo.core.model.StreamPosition
import app.rondo.core.model.Template
import app.rondo.core.nowMillis
import app.rondo.core.searchText
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

class Problem(val status: Int, val code: ErrorCode, message: String) : Exception(message)

/** The tables of an account's own stream, with the column that says whose a row is. */
private val OWN = listOf(
    "decks" to "owner_id",
    "templates" to "owner_id",
    "notes" to "owner_id",
    "events" to "user_id",
    "settings" to "user_id",
    "smart_decks" to "owner_id",
)

/** Pull, push and fetch: :core's rules over Postgres. */
class Sync(private val db: Db, private val clock: () -> Long = ::nowMillis) {
    private val limit = 1000

    fun pull(actor: String, request: PullRequest): PullResponse = db.transaction(repeatable = true) { c ->
        val lends = lends(c, actor)
        val positions = request.streams.associateBy { it.key }
        val keys = listOf("me") + lends.map { it.deckId }
        PullResponse(
            lends,
            keys.map { key ->
                val position = positions[key] ?: StreamPosition(key, 0)
                if (key == "me") own(c, actor, position) else lent(c, lends.first { it.deckId == key }, position)
            },
        )
    }

    private fun lends(c: Connection, actor: String): List<Lend> = c.query(
        "SELECT s.deck_id, s.role, d.owner_id, u.name FROM shares s LEFT JOIN decks d ON d.id = s.deck_id " +
            "LEFT JOIN users u ON u.id = d.owner_id WHERE s.user_id = ?::uuid ORDER BY s.created_at",
        actor,
    ) { Lend(it.getString(1), it.getInt(2), it.getString(3), it.getString(4)) }

    /** An owner's change counter: the latest seq, and the one up to which deleted rows were purged. */
    private fun counter(c: Connection, owner: String): Pair<Long, Long> {
        val sql = "SELECT seq, purged_seq FROM counters WHERE owner_id = ?::uuid"
        return c.query(sql, owner) { it.getLong(1) to it.getLong(2) }.firstOrNull() ?: (0L to 0L)
    }

    /** Nothing new on the stream since [p]. */
    private fun unchanged(p: StreamPosition) =
        StreamPage(p.key, Rows(), p.cursor, more = false, resync = false, fingerprint = p.fingerprint)

    /** The device starts the stream over: rows it hasn't seen were purged meanwhile. */
    private fun resync(p: StreamPosition) = StreamPage(p.key, Rows(), 0, more = false, resync = true)

    /** A row of a stream: its seq, its table and itself as JSON. */
    private fun stamped(r: ResultSet) = Triple(r.getLong(1), r.getString(2), r.getString(3))

    private fun own(c: Connection, actor: String, p: StreamPosition): StreamPage {
        val (seq, purged) = counter(c, actor)
        if (p.cursor in 1 until purged) return resync(p)
        if (seq <= p.cursor) return unchanged(p)
        val ids = c.query("SELECT id FROM decks WHERE owner_id = ?::uuid", actor) { it.getString(1) }
        val union = OWN.joinToString(" UNION ALL ") { (table, whose) ->
            "SELECT seq, '$table', (to_jsonb(x) - 'seq' - 'search_text')::text FROM $table x " +
                "WHERE $whose = ?::uuid AND seq > ?"
        }
        val args = OWN.flatMap { listOf<Any>(actor, p.cursor) } + (limit + 1)
        val rows = c.query("$union ORDER BY 1 LIMIT ?", *args.toTypedArray(), row = ::stamped)
        return page(p, rows, seq, Fingerprint.of(ids), ids)
    }

    private fun lent(c: Connection, lend: Lend, p: StreamPosition): StreamPage {
        val counter = lend.ownerId?.let { counter(c, it) }
        if (counter != null) {
            if (p.cursor in 1 until counter.second) return resync(p)
            if (counter.first <= p.cursor) return unchanged(p)
        }
        val ids = subtree(c, listOf(lend.deckId))
        val sql = "SELECT seq, 'decks', (to_jsonb(d) - 'seq')::text FROM decks d " +
            "WHERE d.id = ANY(?::uuid[]) AND d.seq > ? " +
            "UNION ALL SELECT seq, 'notes', (to_jsonb(n) - 'seq' - 'search_text')::text FROM notes n " +
            "WHERE n.deck_id = ANY(?::uuid[]) AND n.seq > ? " +
            "UNION ALL SELECT seq, 'templates', (to_jsonb(t) - 'seq')::text FROM templates t WHERE t.seq > ? AND " +
            "EXISTS (SELECT 1 FROM notes n WHERE n.template_id = t.id AND n.deck_id = ANY(?::uuid[])) " +
            "ORDER BY 1 LIMIT ?"
        val rows = c.query(sql, ids, p.cursor, ids, p.cursor, p.cursor, ids, limit + 1, row = ::stamped)
        return page(p, rows, counter?.first ?: (rows.maxOfOrNull { it.first } ?: p.cursor), Fingerprint.of(ids), ids)
    }

    private fun page(
        p: StreamPosition,
        found: List<Triple<Long, String, String>>,
        head: Long,
        fingerprint: String,
        ids: List<String>,
    ): StreamPage {
        val more = found.size > limit
        val rows = found.take(limit)
        val byTable = rows.groupBy({ it.second }, { it.third })
        return StreamPage(
            p.key,
            Rows(
                byTable["decks"]?.map { Db.json.decodeFromString<Deck>(it) },
                byTable["templates"]?.map { Db.json.decodeFromString<Template>(it) },
                byTable["notes"]?.map { Db.json.decodeFromString<Note>(it) },
                byTable["events"]?.map { Db.json.decodeFromString<Event>(it) },
                byTable["settings"]?.map { Db.json.decodeFromString<Settings>(it) },
                byTable["smart_decks"]?.map { Db.json.decodeFromString<SmartDeck>(it) },
            ),
            if (more) rows.last().first else maxOf(head, p.cursor),
            more,
            resync = false,
            fingerprint = fingerprint,
            deckIds = ids.takeIf { fingerprint != p.fingerprint },
        )
    }

    /** The decks at and under [roots], deleted ones included. */
    private fun subtree(c: Connection, roots: List<String>): List<String> = c.query(
        "WITH RECURSIVE sub AS (SELECT id FROM decks WHERE id = ANY(?::uuid[]) " +
            "UNION SELECT d.id FROM decks d JOIN sub ON d.parent_id = sub.id) SELECT id FROM sub",
        roots,
    ) { it.getString(1) }

    private fun ancestors(c: Connection, ids: Collection<String>): Map<String, Deck> = c.rows<Deck>(
        "WITH RECURSIVE up AS (SELECT * FROM decks WHERE id = ANY(?::uuid[]) " +
            "UNION SELECT d.* FROM decks d JOIN up ON d.id = up.parent_id) " +
            "SELECT (to_jsonb(up) - 'seq')::text FROM up",
        ids.distinct(),
    ).associateBy { it.id }

    private inline fun <reified T> byId(
        c: Connection,
        table: String,
        ids: Collection<String>,
        key: String = "id",
    ): List<T> {
        if (ids.isEmpty()) return emptyList()
        val sql = "SELECT (to_jsonb(x) - 'seq' - 'search_text')::text FROM $table x WHERE $key = ANY(?::uuid[])"
        return c.rows(sql, ids.distinct())
    }

    private fun access(c: Connection, actor: String, decks: Collection<Deck>): Access {
        val lends = c.query("SELECT deck_id, role FROM shares WHERE user_id = ?::uuid", actor) {
            it.getString(1) to it.getInt(2)
        }.toMap()
        val owners = decks.mapNotNull { it.ownerId }.toSet()
        val sql = "SELECT id FROM users WHERE id = ANY(?::uuid[]) AND pro_until > now()"
        val pro = if (owners.isEmpty()) emptySet() else c.query(sql, owners) { it.getString(1) }.toSet()
        return Access(actor, Tree(decks), lends, pro::contains)
    }

    private fun Access.with(decks: Collection<Deck>) = Access(actor, Tree(decks), lends, pro)

    fun push(actor: String, request: PushRequest): PushResponse = db.transaction { c -> push(c, actor, request.rows) }

    /** Validates and stores [rows] for [actor]; also the write path of hosted MCP. */
    fun push(c: Connection, actor: String, rows: Rows): PushResponse {
        val now = clock()
        val clocks = rows.decks.orEmpty().map { it.v } + rows.templates.orEmpty().map { it.v } +
            rows.notes.orEmpty().map { it.v } + rows.settings.orEmpty().map { it.v } +
            rows.smartDecks.orEmpty().map { it.v }
        if (clocks.any { Rules.clockAhead(it, now) }) {
            throw Problem(409, ErrorCode.CLOCK_AHEAD, "This device's clock is ahead.")
        }
        val incomingNotes = rows.notes.orEmpty()
        val storedNotes = byId<Note>(c, "notes", incomingNotes.map { it.id }).associateBy { it.id }
        // Every deck the rows touch and the decks above them: what the rules decide access by.
        val deckIds = rows.decks.orEmpty().flatMap { listOfNotNull(it.id, it.parentId) } +
            incomingNotes.map { it.deckId } + storedNotes.values.map { it.deckId }
        val decks = HashMap(ancestors(c, deckIds))
        val templateIds = rows.templates.orEmpty().map { it.id } + incomingNotes.map { it.templateId } +
            storedNotes.values.map { it.templateId }
        val storedTemplates = byId<Template>(c, "templates", templateIds).associateBy { it.id }.toMutableMap()
        var access = access(c, actor, decks.values)
        val rejected = ArrayList<Rejection>()
        val writes = ArrayList<Triple<String, String?, JsonObject>>()
        val owners = HashSet<String>()

        fun reject(entity: Rejection.Entity, id: String, code: ErrorCode) {
            rejected += Rejection(entity, id, code)
        }
        fun write(table: String, owner: String?, row: JsonObject) {
            writes += Triple(table, owner, row)
        }

        val storedDecks = HashMap(decks)
        for (d in rows.decks.orEmpty()) {
            val stored = storedDecks[d.id]
            if (stored != null && d.v <= stored.v) continue
            val problem = Rules.deck(access, stored, d)
            if (problem != null) {
                reject(Rejection.Entity.DECK, d.id, problem)
                continue
            }
            if (stored != null && d.deletedAt != null && stored.deletedAt == null && release(c, d)) {
                owners += stored.ownerId!!
                continue
            }
            decks[d.id] = d
            access = access.with(decks.values)
            write("decks", d.ownerId, Db.json.encodeToJsonElement(d).jsonObject)
        }
        for (t in rows.templates.orEmpty()) {
            val stored = storedTemplates[t.id]
            if (stored != null && t.v <= stored.v) continue
            val problem = Rules.template(access, stored, t)
            if (problem != null) {
                reject(Rejection.Entity.TEMPLATE, t.id, problem)
                continue
            }
            if (stored != null && t.deletedAt != null && stored.deletedAt == null) {
                val inUse = c.query(
                    "SELECT owner_id IS NOT DISTINCT FROM ?::uuid FROM notes " +
                        "WHERE template_id = ?::uuid AND deleted_at IS NULL GROUP BY 1",
                    t.ownerId,
                    t.id,
                ) { it.getBoolean(1) }
                if (true in inUse) {
                    reject(Rejection.Entity.TEMPLATE, t.id, ErrorCode.TEMPLATE_IN_USE)
                    continue
                }
                if (false in inUse) {
                    c.exec("UPDATE templates SET owner_id = NULL WHERE id = ?::uuid", t.id)
                    owners += t.ownerId!!
                    continue
                }
            }
            storedTemplates[t.id] = t
            write("templates", t.ownerId, Db.json.encodeToJsonElement(t).jsonObject)
        }
        for (n in incomingNotes) {
            val stored = storedNotes[n.id]
            if (stored != null && n.v <= stored.v) continue
            val template = Templates.builtin(n.templateId) ?: storedTemplates[n.templateId]
            val problem = Rules.note(access, stored, n, template)
            if (problem != null) {
                reject(Rejection.Entity.NOTE, n.id, problem)
                continue
            }
            val search = searchText(n.fields, Templates.textFields(template!!))
            // An app that predates tags sends none: the note keeps the ones it has.
            val tagged = if (n.tags == null) n.copy(tags = stored?.tags.orEmpty()) else n
            val row = Db.json.encodeToJsonElement(tagged).jsonObject + ("search_text" to JsonPrimitive(search))
            write("notes", n.ownerId, JsonObject(row))
            // The media a note shows stay stored while it does (the media cleanup job reads note_media).
            c.exec("DELETE FROM note_media WHERE note_id = ?::uuid", n.id)
            val media = "INSERT INTO note_media (note_id, hash) VALUES (?::uuid, decode(?, 'hex')) " +
                "ON CONFLICT DO NOTHING"
            for (hash in Templates.media(template, n)) c.exec(media, n.id, hash)
        }
        for (e in rows.events.orEmpty()) {
            val problem = Rules.event(access, e, now)
            if (problem != null) {
                reject(Rejection.Entity.EVENT, e.id, problem)
            } else {
                write("events", actor, Db.json.encodeToJsonElement(e).jsonObject)
            }
        }
        for (s in rows.settings.orEmpty()) {
            val stored = byId<Settings>(c, "settings", listOf(s.userId), "user_id").firstOrNull()
            if (stored != null && s.v <= stored.v) continue
            val problem = Rules.settings(access, s)
            if (problem != null) {
                reject(Rejection.Entity.SETTINGS, s.userId, problem)
            } else {
                write("settings", actor, Db.json.encodeToJsonElement(s).jsonObject)
            }
        }

        val incomingSmart = rows.smartDecks.orEmpty()
        val storedSmart = byId<SmartDeck>(c, "smart_decks", incomingSmart.map { it.id }).associateBy { it.id }
        for (s in incomingSmart) {
            val stored = storedSmart[s.id]
            if (stored != null && s.v <= stored.v) continue
            val problem = Rules.smartDeck(access, stored, s)
            if (problem != null) {
                reject(Rejection.Entity.SMART_DECK, s.id, problem)
            } else {
                write("smart_decks", s.ownerId, Db.json.encodeToJsonElement(s).jsonObject)
            }
        }

        val byOwner = writes.groupBy { it.second }
        for (owner in (byOwner.keys.filterNotNull() + owners).toSortedSet()) {
            val count = byOwner[owner].orEmpty().size.toLong().coerceAtLeast(1)
            val end = try {
                c.exec("INSERT INTO counters (owner_id) VALUES (?::uuid) ON CONFLICT DO NOTHING", owner)
                c.query("UPDATE counters SET seq = seq + ? WHERE owner_id = ?::uuid RETURNING seq", count, owner) {
                    it.getLong(1)
                }.single()
            } catch (e: SQLException) {
                throw Problem(401, ErrorCode.UNAUTHORIZED, "The account is not set up yet.")
            }
            byOwner[owner].orEmpty().forEachIndexed { i, (table, _, row) ->
                db.upsert(c, table, JsonObject(row + ("seq" to JsonPrimitive(end - count + 1 + i))))
            }
        }
        return PushResponse(writes.size, rejected)
    }

    /**
     * When a deck someone borrows is deleted (the lent deck or one above it), everything lent stays,
     * without its author: owner_id becomes NULL. Returns true when [deck] itself was lent and so is
     * released instead of deleted.
     */
    private fun release(c: Connection, deck: Deck): Boolean {
        val sql = "SELECT DISTINCT deck_id FROM shares WHERE deck_id = ANY(?::uuid[])"
        val lent = c.query(sql, subtree(c, listOf(deck.id))) { it.getString(1) }
        if (lent.isEmpty()) return false
        val freed = subtree(c, lent)
        c.exec("UPDATE decks SET owner_id = NULL WHERE id = ANY(?::uuid[])", freed)
        c.exec("UPDATE notes SET owner_id = NULL WHERE deck_id = ANY(?::uuid[])", freed)
        return deck.id in lent
    }

    fun fetch(actor: String, request: FetchRequest): Rows = db.transaction { c ->
        val wanted = request.decks.orEmpty()
        val access = access(c, actor, ancestors(c, wanted).values)
        val readable = wanted.filter { access.role(it) != Role.NONE }
        val notes = byId<Note>(c, "notes", readable, "deck_id")
        val templates = request.templates.orEmpty() + notes.map { it.templateId }.filterNot(Templates::isBuiltin)
        Rows(readable.mapNotNull { access.tree[it] }, byId<Template>(c, "templates", templates), notes)
    }
}
