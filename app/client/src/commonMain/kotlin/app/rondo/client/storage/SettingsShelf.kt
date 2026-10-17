package app.rondo.client.storage

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.rondo.client.db.Settings as SettingsRow
import app.rondo.core.kernel.infrastructure.Record
import app.rondo.core.study.domain.Days
import app.rondo.core.study.domain.Fsrs
import app.rondo.core.study.domain.Settings
import app.rondo.core.study.infrastructure.SettingsRecord

/** The learner's settings: one row, theirs alone. */
class SettingsShelf internal constructor(private val store: Store) : Shelf {
    private val q get() = store.q

    /** As saved, or a new learner's: four answer buttons, the system's theme, text at 100%. */
    suspend fun get(): Settings =
        q.settings().awaitAsOneOrNull()?.record()?.settings() ?: Settings(store.me, 4, 0, 100, 0)

    /** Keeps [settings], to push. */
    suspend fun save(settings: Settings) = put(SettingsRecord(settings), dirty = true)

    /** FSRS with the learner's weights, for a deck's [retention] percent; [calendar] counts days as the learner lives them. */
    suspend fun fsrs(retention: Int = 90, calendar: Boolean = false): Fsrs {
        val s = get()
        return Fsrs(s.weights, retention / 100.0, Days(s.timezone).takeIf { calendar })
    }

    private suspend fun put(r: SettingsRecord, dirty: Boolean) = q.putSettings(
        SettingsRow(
            1, r.grading.toLong(), r.theme.toLong(), r.textSize.toLong(), r.timezone, r.fsrsWeights, r.v, dirty,
            r.reminderAt?.toLong(),
        ),
    )

    private fun SettingsRow.record() = SettingsRecord(
        store.me,
        grading.toInt(),
        theme.toInt(),
        text_size.toInt(),
        timezone,
        fsrs_weights,
        reminder_at?.toInt(),
        v,
    )

    override suspend fun unsent(): List<Record> = listOfNotNull(
        q.settings().awaitAsOneOrNull()?.takeIf {
            it.dirty
        }?.record(),
    )

    override suspend fun sent(records: List<Record>) {
        for (r in records) if (r is SettingsRecord) q.cleanSettings(r.v)
    }

    override suspend fun keep(record: Record, effects: Effects) {
        if (record !is SettingsRecord) return
        store.hlc.observe(record.v)
        val local = q.settings().awaitAsOneOrNull()
        if (local != null && local.dirty && record.v < local.v) return
        effects.weights = effects.weights || local?.fsrs_weights != record.fsrsWeights
        put(record, dirty = false)
    }

    override suspend fun refused(record: Record, code: String, effects: Effects): String? = null
}
