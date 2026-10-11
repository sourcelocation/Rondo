package app.rondo.client.storage

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.rondo.client.db.Rejects as RejectRow
import app.rondo.core.record.Record
import kotlin.time.Clock

/** A change the server refused: [label] names it, [code] says why, [record] is this device's version. */
class Reject(val id: String, val entity: String, val code: String, val label: String, val record: Record?, val at: Long)

/** The changes the server refused, kept for the sync issues panel until they're dealt with. */
class Rejects internal constructor(private val store: Store) {
    private val q get() = store.q

    /** The latest first. A version an older app kept, which this one can't read, has no [Reject.record]. */
    suspend fun all(): List<Reject> = q.rejects().awaitAsList().map {
        val record = runCatching { Record.json.decodeFromString(Record.serializer(), it.body) }.getOrNull()
        Reject(it.id, it.entity, it.code, it.label, record, it.at)
    }

    suspend fun put(entity: String, record: Record, code: String, label: String) = q.putReject(
        RejectRow(
            record.id,
            entity,
            code,
            label,
            Record.json.encodeToString(Record.serializer(), record),
            Clock.System.now().toEpochMilliseconds(),
        ),
    )

    suspend fun drop(id: String, entity: String) = q.dropReject(id, entity)
}
