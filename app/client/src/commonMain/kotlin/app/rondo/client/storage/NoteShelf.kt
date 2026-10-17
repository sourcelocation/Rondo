package app.rondo.client.storage

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.rondo.client.db.Notes
import app.rondo.core.kernel.infrastructure.Record
import app.rondo.core.markup.domain.Folded
import app.rondo.core.markup.domain.Markup
import app.rondo.core.notes.domain.Note
import app.rondo.core.notes.domain.NoteFilter
import app.rondo.core.notes.domain.NoteSort
import app.rondo.core.notes.domain.Tags
import app.rondo.core.notes.domain.Template
import app.rondo.core.notes.infrastructure.NoteQuery
import app.rondo.core.notes.infrastructure.NoteRecord
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** Notes, each kept with the text it's found by. */
class NoteShelf internal constructor(private val store: Store) : Shelf {
    private val q get() = store.q

    suspend fun get(id: String): Note? = q.note(id).awaitAsOneOrNull()?.note()

    /** The notes of [ids] there are, in no order. */
    suspend fun of(ids: Collection<String>): List<Note> =
        ids.chunked(500).flatMap { q.notesById(it).awaitAsList() }.map { it.note() }

    /** The live notes in [decks], in the order they were added. */
    suspend fun inDecks(decks: Collection<String>): List<Note> =
        decks.chunked(500).flatMap { q.notesIn(it, Long.MAX_VALUE).awaitAsList() }.map { it.note() }.sortedBy { it.id }

    /** Every note's id, deleted ones included. */
    suspend fun ids(): Set<String> = q.noteIds().awaitAsList().toSet()

    /** [owner]'s notes deleted lately, the latest first. */
    suspend fun deleted(owner: String): List<Note> = q.deletedNotes(owner, ::Notes).awaitAsList().map { it.note() }

    /** The decks holding live notes that keep Anki's format. */
    suspend fun ankiDecks(): Set<String> = q.ankiDecks().awaitAsList().toSet()

    /** Keeps [note] of [template], to push. */
    suspend fun save(note: Note, template: Template) = put(NoteRecord(note), template, dirty = true)

    /** Takes back a note made here and not pushed yet, as if it never was; whether it could. */
    suspend fun forget(note: Note): Boolean {
        q.dropUnsyncedNote(note.id, note.v)
        if (get(note.id) != null) return false
        store.cards.drop(listOf(note.id))
        return true
    }

    /** Takes the notes in [decks] off this device, not the server: decks that left a stream. */
    internal suspend fun dropIn(decks: Collection<String>) {
        val notes = decks.chunked(500).flatMap { q.notesIn(it, Long.MAX_VALUE).awaitAsList() }.map { it.id }
        store.cards.drop(notes)
        decks.chunked(500).forEach { q.dropNotesIn(it) }
    }

    /**
     * Notes [filter] finds (Browse and the command-line MCP tools), in [sort] order.
     */
    suspend fun search(filter: NoteFilter, limit: Int, sort: NoteSort = NoteSort.ADDED): List<Note> {
        val where = NoteQuery(filter).notes(cardStates = true)
        val due = "(SELECT min(c.due) FROM card_state c WHERE c.note_id = n.id AND c.state > 0)"
        val order = when (sort) {
            NoteSort.ADDED -> "n.id DESC"
            NoteSort.OLDEST -> "n.id"
            NoteSort.CHANGED -> "n.v DESC"
            NoteSort.DUE -> "$due IS NULL, $due"
            NoteSort.ALPHA -> "n.search_text"
        }
        val sql = "SELECT n.id FROM notes n WHERE ${where.where} ORDER BY $order LIMIT ?"
        val ids = store.select(sql, where.args + limit.toLong()) { it.id(0) }
        val notes = of(ids).associateBy { it.id }
        return ids.mapNotNull(notes::get)
    }

    /**
     * Tags notes have that contain [text] (case aside), with the tags above them, alphabetically;
     * with no text, only the top-level ones.
     */
    suspend fun tags(text: String, limit: Int): List<String> {
        val wanted = text.trim().lowercase()
        val found = HashMap<String, String>()
        for (row in q.noteTags(wanted).awaitAsList()) {
            for (tag in Tags(row).list) {
                val levels = tag.split("::")
                for (i in levels.indices) {
                    val t = levels.take(i + 1).joinToString("::")
                    val shown = if (wanted.isEmpty()) i == 0 else wanted in t.lowercase()
                    if (shown) found.getOrPut(t.lowercase()) { t }
                }
            }
        }
        return found.values.sortedBy { it.lowercase() }.take(limit)
    }

    /** A note of a type this device doesn't have yet is found by all its text until the type arrives. */
    private suspend fun put(r: NoteRecord, template: Template?, dirty: Boolean) {
        val note = r.note()
        val search = template?.searchText(note)
            ?: note.fields.values.joinToString(" ") { Folded(Markup(it).plain).text.replace('\n', ' ') }
        val fields = Record.json.encodeToString(FIELDS, r.fields)
        q.putNote(Notes(r.id, r.ownerId, r.deckId, r.templateId, fields, search, r.v, r.deletedAt, dirty, note.tags))
    }

    private fun Notes.note() = record().note()

    private fun Notes.record() = NoteRecord(
        id,
        owner_id,
        deck_id,
        template_id,
        Record.json.decodeFromString(FIELDS, fields),
        v,
        deleted_at,
        tags,
    )

    override suspend fun unsent(): List<Record> = q.dirtyNotes().awaitAsList().map { it.record() }

    override suspend fun sent(records: List<Record>) {
        for (r in records) if (r is NoteRecord) q.cleanNote(r.id, r.v)
    }

    override suspend fun keep(record: Record, effects: Effects) {
        if (record !is NoteRecord) return
        store.hlc.observe(record.v)
        val local = q.note(record.id).awaitAsOneOrNull()
        if (local != null && local.dirty && record.v < local.v) return
        val template = store.templates.get(record.templateId)
        if (template == null) effects.templates += record.templateId
        put(record, template, dirty = false)
        effects.notes += record.id
    }

    override suspend fun refused(record: Record, code: String, effects: Effects): String? {
        if (record !is NoteRecord) return null
        effects.decks += record.deckId
        val note = record.note()
        return store.templates.get(note.templateId)?.label(note) ?: "…"
    }

    private companion object {
        val FIELDS = MapSerializer(String.serializer(), String.serializer())
    }
}
