package app.rondo.client.storage

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.rondo.client.db.Smart_decks
import app.rondo.core.kernel.infrastructure.Record
import app.rondo.core.study.domain.SmartDeck
import app.rondo.core.study.infrastructure.FilterRecord
import app.rondo.core.study.infrastructure.SmartDeckRecord

/** The learner's smart decks. */
class SmartDeckShelf internal constructor(private val store: Store) : Shelf {
    private val q get() = store.q

    /** The live ones, in their order. */
    suspend fun all(): List<SmartDeck> = q.smartDecks().awaitAsList().map { it.record().smartDeck() }

    suspend fun get(id: String): SmartDeck? = q.smartDeck(id).awaitAsOneOrNull()?.record()?.smartDeck()

    /** Keeps [smart], to push. */
    suspend fun save(smart: SmartDeck) = put(SmartDeckRecord(smart), dirty = true)

    private suspend fun put(r: SmartDeckRecord, dirty: Boolean) = q.putSmartDeck(
        Smart_decks(
            r.id, r.ownerId, r.name, r.position, r.icon, r.color.toLong(),
            Record.json.encodeToString(FilterRecord.serializer(), r.filter), r.newPerDay.toLong(),
            r.reviewsPerDay.toLong(), r.v, r.deletedAt, dirty,
        ),
    )

    private fun Smart_decks.record() = SmartDeckRecord(
        id, owner_id, name, position, icon, color.toInt(),
        Record.json.decodeFromString(
            FilterRecord.serializer(),
            filter,
        ),
        new_per_day.toInt(), reviews_per_day.toInt(), v, deleted_at,
    )

    override suspend fun unsent(): List<Record> = q.dirtySmartDecks().awaitAsList().map { it.record() }

    override suspend fun sent(records: List<Record>) {
        for (r in records) if (r is SmartDeckRecord) q.cleanSmartDeck(r.id, r.v)
    }

    override suspend fun keep(record: Record, effects: Effects) {
        if (record !is SmartDeckRecord) return
        store.hlc.observe(record.v)
        val local = q.smartDeck(record.id).awaitAsOneOrNull()
        if (local == null || !local.dirty || record.v >= local.v) put(record, dirty = false)
    }

    override suspend fun refused(record: Record, code: String, effects: Effects): String? =
        (record as? SmartDeckRecord)?.name
}
