package app.rondo.client.storage

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.rondo.client.db.Templates
import app.rondo.core.Template
import app.rondo.core.record.Record
import app.rondo.core.record.TemplateRecord

/** Note types: Rondo's own, never stored, and those on this device (yours, and those lent decks use). */
class TemplateShelf internal constructor(private val store: Store) : Shelf {
    private val q get() = store.q

    /** Every note type by id: Rondo's own first, then the stored ones. */
    suspend fun all(): Map<String, Template> =
        Template.builtins + q.templates().awaitAsList().mapNotNull { it.template() }.associateBy { it.id }

    suspend fun get(id: String): Template? = Template.builtins[id] ?: q.template(id).awaitAsOneOrNull()?.template()

    /** Keeps [template], to push. */
    suspend fun save(template: Template) = put(TemplateRecord(template), dirty = true)

    /** How many live notes use each note type. */
    suspend fun use(): Map<String, Int> = q.templateUse().awaitAsList().associate { it.template_id to it.notes.toInt() }

    suspend fun inUse(id: String): Boolean = q.notesUsing(id).awaitAsOne() > 0

    /** Takes off this device the note types of others that no note uses any more. */
    internal suspend fun dropUnused() = q.dropUnusedTemplates(store.me)

    private suspend fun put(r: TemplateRecord, dirty: Boolean) {
        q.putTemplate(
            Templates(
                r.id,
                r.ownerId,
                r.name,
                r.kind.toLong(),
                Record.json.encodeToString(TemplateRecord.Def.serializer(), r.def),
                r.v,
                r.deletedAt,
                dirty,
            ),
        )
    }

    /** Null for a kind this version can't read: it stays stored, unshown, until an update can. */
    private fun Templates.template(): Template? = record().template()

    private fun Templates.record() = TemplateRecord(
        id,
        owner_id,
        name,
        kind.toInt(),
        Record.json.decodeFromString(TemplateRecord.Def.serializer(), def),
        v,
        deleted_at,
    )

    override suspend fun unsent(): List<Record> = q.dirtyTemplates().awaitAsList().map { it.record() }

    override suspend fun sent(records: List<Record>) {
        for (r in records) if (r is TemplateRecord) q.cleanTemplate(r.id, r.v)
    }

    override suspend fun keep(record: Record, effects: Effects) {
        if (record !is TemplateRecord) return
        store.hlc.observe(record.v)
        val local = q.template(record.id).awaitAsOneOrNull()
        if (local == null || !local.dirty || record.v >= local.v) put(record, dirty = false)
    }

    override suspend fun refused(record: Record, code: String, effects: Effects): String? {
        if (record !is TemplateRecord) return null
        effects.templates += record.id
        return record.name
    }
}
