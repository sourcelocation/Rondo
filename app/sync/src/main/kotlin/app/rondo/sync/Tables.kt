package app.rondo.sync

import app.rondo.core.Access
import app.rondo.core.Deck
import app.rondo.core.ErrorCode
import app.rondo.core.Note
import app.rondo.core.Settings
import app.rondo.core.SmartDeck
import app.rondo.core.Template
import app.rondo.core.Tree
import app.rondo.core.record.DeckRecord
import app.rondo.core.record.EventRecord
import app.rondo.core.record.NoteRecord
import app.rondo.core.record.Record
import app.rondo.core.record.SettingsRecord
import app.rondo.core.record.SmartDeckRecord
import app.rondo.core.record.TemplateRecord
import app.rondo.core.sync.Rejection
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * One synced table, as the sync service sees it: its rows as records, what a push of its kind is
 * checked against and how it's kept, and its part of each stream. Each table turns its own rows
 * into records and back; the service only loops.
 */
interface Table {
    /** Its name in Postgres, and what a refusal calls its kind. */
    val name: String
    val entity: String

    /** Its key column, and the column saying whose a row is. */
    val key: String get() = "id"
    val owner: String get() = "owner_id"

    /** Whether a newer version replaces a row; events are only ever added. */
    val replaces: Boolean get() = true

    /** Selects `seq, name, row` of its rows in a lent deck's subtree (`?::uuid[]`) changed after `?`; null when none are lent. */
    val lent: String? get() = null

    /** A stored row, read as JSON. */
    fun record(json: String): Record

    /** Loads into [batch] what the [records] of its kind are checked against. */
    fun load(records: List<Record>, batch: Batch) {}

    /** Checks [record], when it's of its kind, as [batch]'s actor's, and keeps it in [batch] or refuses it. */
    fun accept(record: Record, batch: Batch)
}

/** A push being checked: the stored versions it's checked against, what it keeps, and what it refuses. */
class Batch(val sql: Sql, val actor: String, val now: Long) {
    val decks = HashMap<String, Deck>()
    val templates = HashMap<String, Template>()
    val notes = HashMap<String, Note>()
    val smartDecks = HashMap<String, SmartDeck>()
    var settings: Settings? = null

    /** Who may do what, with the decks kept so far. */
    lateinit var access: Access

    internal val rejected = ArrayList<Rejection>()
    internal val writes = ArrayList<Write>()

    /** Owners whose rows changed without a write of theirs: a stream must still move on. */
    internal val touched = HashSet<String>()

    class Write(val table: Table, val owner: String?, val row: JsonObject)

    fun reject(table: Table, id: String, code: ErrorCode) {
        rejected += Rejection(table.entity, id, code.value)
    }

    fun write(table: Table, owner: String?, row: JsonObject) {
        writes += Write(table, owner, row)
    }

    /** Keeps [deck], so what comes after it in the push sees it. */
    fun keep(deck: Deck) {
        decks[deck.id] = deck
        access = Access(access.actor, Tree(decks.values), access.lends, access.pro)
    }

    internal fun <T> json(serializer: KSerializer<T>, record: T): JsonObject =
        Record.json.encodeToJsonElement(serializer, record).jsonObject
}

class DeckTable : Table {
    override val name = "decks"
    override val entity = "deck"
    override val lent = "SELECT seq, 'decks', (to_jsonb(d) - 'seq')::text FROM decks d WHERE d.id = ANY(?::uuid[]) AND d.seq > ?"

    override fun record(json: String) = Record.json.decodeFromString(DeckRecord.serializer(), json)

    /** The decks at and under [roots], deleted ones included. */
    fun subtree(sql: Sql, roots: Collection<String>): List<String> = sql.query(
        "WITH RECURSIVE sub AS (SELECT id FROM decks WHERE id = ANY(?::uuid[]) " +
            "UNION SELECT d.id FROM decks d JOIN sub ON d.parent_id = sub.id) SELECT id FROM sub",
        roots,
    ) { it.getString(1) }

    /** [ids] and the decks above them. */
    fun ancestors(sql: Sql, ids: Collection<String>): List<Deck> = sql.query(
        "WITH RECURSIVE up AS (SELECT * FROM decks WHERE id = ANY(?::uuid[]) " +
            "UNION SELECT d.* FROM decks d JOIN up ON d.id = up.parent_id) SELECT (to_jsonb(up) - 'seq')::text FROM up",
        ids.distinct(),
    ) { record(it.getString(1)).deck() }

    /** Loads [ids] and the decks above them into [batch], unless they're there. */
    fun need(ids: Collection<String>, batch: Batch) {
        val missing = ids.filter { it !in batch.decks }
        if (missing.isNotEmpty()) for (d in ancestors(batch.sql, missing)) batch.decks.putIfAbsent(d.id, d)
    }

    override fun load(records: List<Record>, batch: Batch) =
        need(records.filterIsInstance<DeckRecord>().flatMap { listOfNotNull(it.id, it.parentId) }, batch)

    override fun accept(record: Record, batch: Batch) {
        if (record !is DeckRecord) return
        val d = record.deck()
        val stored = batch.decks[d.id]
        if (stored != null && d.v <= stored.v) return
        d.check(batch.access, stored)?.let { return batch.reject(this, d.id, it) }
        if (stored != null && d.deletedAt != null && stored.deletedAt == null && release(batch.sql, d)) {
            batch.touched += stored.ownerId!!
            return
        }
        batch.keep(d)
        batch.write(this, d.ownerId, batch.json(DeckRecord.serializer(), record))
    }

    /**
     * When a deck someone borrows is deleted (the lent deck or one above it), everything lent stays,
     * without its author: owner_id becomes NULL. Whether [deck] itself was lent, and so is released
     * instead of deleted.
     */
    private fun release(sql: Sql, deck: Deck): Boolean {
        val lent = sql.query("SELECT DISTINCT deck_id FROM shares WHERE deck_id = ANY(?::uuid[])", subtree(sql, listOf(deck.id))) {
            it.getString(1)
        }
        if (lent.isEmpty()) return false
        val freed = subtree(sql, lent)
        sql.exec("UPDATE decks SET owner_id = NULL WHERE id = ANY(?::uuid[])", freed)
        sql.exec("UPDATE notes SET owner_id = NULL WHERE deck_id = ANY(?::uuid[])", freed)
        return deck.id in lent
    }
}

class TemplateTable : Table {
    override val name = "templates"
    override val entity = "template"
    override val lent = "SELECT seq, 'templates', (to_jsonb(t) - 'seq')::text FROM templates t WHERE EXISTS " +
        "(SELECT 1 FROM notes n WHERE n.template_id = t.id AND n.deck_id = ANY(?::uuid[])) AND t.seq > ?"

    override fun record(json: String) = Record.json.decodeFromString(TemplateRecord.serializer(), json)

    /** The stored note types of [ids], deleted ones included; those this server can't read left out. */
    fun byId(sql: Sql, ids: Collection<String>): List<TemplateRecord> =
        if (ids.isEmpty()) emptyList() else sql.query(
            "SELECT (to_jsonb(t) - 'seq')::text FROM templates t WHERE id = ANY(?::uuid[])",
            ids.distinct(),
        ) { record(it.getString(1)) }

    /** Loads [ids] into [batch], unless they're there or Rondo's own. */
    fun need(ids: Collection<String>, batch: Batch) {
        val missing = ids.filter { it !in batch.templates && it !in Template.builtins }
        for (r in byId(batch.sql, missing)) r.template()?.let { batch.templates.putIfAbsent(it.id, it) }
    }

    override fun load(records: List<Record>, batch: Batch) = need(records.filterIsInstance<TemplateRecord>().map { it.id }, batch)

    override fun accept(record: Record, batch: Batch) {
        if (record !is TemplateRecord) return
        val t = record.template() ?: return batch.reject(this, record.id, ErrorCode.INVALID)
        val stored = batch.templates[t.id]
        if (stored != null && t.v <= stored.v) return
        t.check(batch.access, stored)?.let { return batch.reject(this, t.id, it) }
        if (stored != null && t.deletedAt != null && stored.deletedAt == null) {
            val inUse = batch.sql.query(
                "SELECT owner_id IS NOT DISTINCT FROM ?::uuid FROM notes WHERE template_id = ?::uuid AND deleted_at IS NULL GROUP BY 1",
                t.ownerId,
                t.id,
            ) { it.getBoolean(1) }
            // Your own notes use it: it stays. Only notes others borrow do: it's theirs now, without you.
            if (true in inUse) return batch.reject(this, t.id, ErrorCode.TEMPLATE_IN_USE)
            if (false in inUse) {
                batch.sql.exec("UPDATE templates SET owner_id = NULL WHERE id = ?::uuid", t.id)
                batch.touched += t.ownerId!!
                return
            }
        }
        batch.templates[t.id] = t
        batch.write(this, t.ownerId, batch.json(TemplateRecord.serializer(), record))
    }
}

/** Notes, each kept with the text it's found by and the media it shows (which keeps them stored). */
class NoteTable(private val decks: DeckTable, private val templates: TemplateTable) : Table {
    override val name = "notes"
    override val entity = "note"
    override val lent = "SELECT seq, 'notes', (to_jsonb(n) - 'seq' - 'search_text')::text FROM notes n " +
        "WHERE n.deck_id = ANY(?::uuid[]) AND n.seq > ?"

    override fun record(json: String) = Record.json.decodeFromString(NoteRecord.serializer(), json)

    /** The live notes in [deckIds]. */
    fun inDecks(sql: Sql, deckIds: Collection<String>): List<NoteRecord> = rows(sql, "deck_id = ANY(?::uuid[]) AND deleted_at IS NULL", deckIds)

    fun byId(sql: Sql, ids: Collection<String>): List<NoteRecord> = rows(sql, "id = ANY(?::uuid[])", ids)

    private fun rows(sql: Sql, where: String, ids: Collection<String>): List<NoteRecord> =
        if (ids.isEmpty()) emptyList() else sql.query("SELECT (to_jsonb(n) - 'seq' - 'search_text')::text FROM notes n WHERE $where", ids.distinct()) {
            record(it.getString(1))
        }

    override fun load(records: List<Record>, batch: Batch) {
        val incoming = records.filterIsInstance<NoteRecord>()
        for (r in byId(batch.sql, incoming.map { it.id })) batch.notes[r.id] = r.note()
        val stored = batch.notes.values
        decks.need(incoming.map { it.deckId } + stored.map { it.deckId }, batch)
        templates.need(incoming.map { it.templateId } + stored.map { it.templateId }, batch)
    }

    override fun accept(record: Record, batch: Batch) {
        if (record !is NoteRecord) return
        val stored = batch.notes[record.id]
        if (stored != null && record.v <= stored.v) return
        // An app that predates tags sends none: the note keeps the ones it has.
        val kept = if (record.tags == null) NoteRecord(record.id, record.ownerId, record.deckId, record.templateId, record.fields, record.v, record.deletedAt, stored?.tags.orEmpty()) else record
        val n = kept.note()
        val template = Template.builtins[n.templateId] ?: batch.templates[n.templateId]
        n.check(batch.access, stored, template)?.let { return batch.reject(this, n.id, it) }
        val row = batch.json(NoteRecord.serializer(), kept) + ("search_text" to JsonPrimitive(template!!.searchText(n)))
        batch.write(this, n.ownerId, JsonObject(row))
        // The media a note shows stay stored while it does (the media cleanup job reads note_media).
        batch.sql.exec("DELETE FROM note_media WHERE note_id = ?::uuid", n.id)
        for (hash in template.media(n)) {
            batch.sql.exec("INSERT INTO note_media (note_id, hash) VALUES (?::uuid, decode(?, 'hex')) ON CONFLICT DO NOTHING", n.id, hash)
        }
    }
}

class EventTable : Table {
    override val name = "events"
    override val entity = "event"
    override val owner = "user_id"
    override val replaces = false

    override fun record(json: String) = Record.json.decodeFromString(EventRecord.serializer(), json)

    override fun accept(record: Record, batch: Batch) {
        if (record !is EventRecord) return
        val e = record.event() ?: return batch.reject(this, record.id, ErrorCode.INVALID)
        e.check(batch.access, batch.now)?.let { return batch.reject(this, e.id, it) }
        batch.write(this, batch.actor, batch.json(EventRecord.serializer(), record))
    }
}

class SettingsTable : Table {
    override val name = "settings"
    override val entity = "settings"
    override val key = "user_id"
    override val owner = "user_id"

    override fun record(json: String) = Record.json.decodeFromString(SettingsRecord.serializer(), json)

    override fun load(records: List<Record>, batch: Batch) {
        if (records.none { it is SettingsRecord }) return
        batch.settings = batch.sql.query("SELECT (to_jsonb(s) - 'seq')::text FROM settings s WHERE user_id = ?::uuid", batch.actor) {
            record(it.getString(1)).settings()
        }.firstOrNull()
    }

    override fun accept(record: Record, batch: Batch) {
        if (record !is SettingsRecord) return
        val s = record.settings()
        val stored = batch.settings
        if (stored != null && s.v <= stored.v) return
        s.check(batch.access)?.let { return batch.reject(this, s.userId, it) }
        batch.settings = s
        batch.write(this, batch.actor, batch.json(SettingsRecord.serializer(), record))
    }
}

class SmartDeckTable : Table {
    override val name = "smart_decks"
    override val entity = "smart_deck"

    override fun record(json: String) = Record.json.decodeFromString(SmartDeckRecord.serializer(), json)

    override fun load(records: List<Record>, batch: Batch) {
        val ids = records.filterIsInstance<SmartDeckRecord>().map { it.id }
        if (ids.isEmpty()) return
        batch.sql.query("SELECT (to_jsonb(s) - 'seq')::text FROM smart_decks s WHERE id = ANY(?::uuid[])", ids) {
            record(it.getString(1)).smartDeck()
        }.forEach { batch.smartDecks[it.id] = it }
    }

    override fun accept(record: Record, batch: Batch) {
        if (record !is SmartDeckRecord) return
        val s = record.smartDeck()
        val stored = batch.smartDecks[s.id]
        if (stored != null && s.v <= stored.v) return
        s.check(batch.access, stored)?.let { return batch.reject(this, s.id, it) }
        batch.smartDecks[s.id] = s
        batch.write(this, s.ownerId, batch.json(SmartDeckRecord.serializer(), record))
    }
}
