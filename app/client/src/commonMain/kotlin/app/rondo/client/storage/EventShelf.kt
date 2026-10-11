package app.rondo.client.storage

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.rondo.client.db.Events
import app.rondo.core.Event
import app.rondo.core.record.EventRecord
import app.rondo.core.record.Record

/** The learner's events on this device: their own, made here or pulled, and only ever added. */
class EventShelf internal constructor(private val store: Store) : Shelf {
    private val q get() = store.q

    /** Keeps [event], to push. */
    suspend fun save(event: Event) = put(EventRecord(event), pushed = false)

    /**
     * The events about [subjects] (notes, or decks for their unlocks), with the voids that take any
     * of them back.
     */
    suspend fun of(subjects: Collection<String>): List<Event> {
        val own = subjects.chunked(500).flatMap { q.eventsIn(it).awaitAsList() }
        val voids = own.map { it.id }.chunked(500).flatMap { q.eventsIn(it).awaitAsList() }
        return (own + voids).mapNotNull { it.event() }
    }

    /** Whether any of [ids] is on this device. */
    suspend fun any(ids: Collection<String>): Boolean =
        ids.chunked(500).any { q.eventIds(it).awaitAsList().isNotEmpty() }

    /** Whether [event] reached the server. */
    suspend fun pushed(event: Event): Boolean = q.event(event.id).awaitAsOneOrNull()?.pushed == true

    /** Takes back [event] if it hasn't reached the server; whether it could. */
    suspend fun forget(event: Event): Boolean {
        q.dropUnpushedEvent(event.id)
        return q.event(event.id).awaitAsOneOrNull() == null
    }

    /** The decks opened by hand. */
    suspend fun unlocked(): Set<String> = q.unlocked().awaitAsList().toSet()

    /** The answers since [at], oldest first. */
    suspend fun reviewsSince(at: Long): List<Event.Review> =
        q.reviewsSince(at).awaitAsList().mapNotNull { it.event() as? Event.Review }

    /** Every answer not taken back, card by card, oldest first: what FSRS's weights are fitted to. */
    suspend fun reviews(): List<Event.Review> = q.allReviews().awaitAsList().mapNotNull { it.event() as? Event.Review }

    private suspend fun put(r: EventRecord, pushed: Boolean) = q.putEvent(
        Events(
            r.id, r.subjectId, r.card.toLong(), r.kind.toLong(), r.value?.toLong(), r.at, r.durationMs?.toLong(), r.due,
            r.stability, r.difficulty, r.reps?.toLong(), r.lapses?.toLong(), pushed,
        ),
    )

    private fun Events.event(): Event? = record().event()

    private fun Events.record() = EventRecord(
        id, store.me, subject_id, card.toInt(), kind.toInt(), value_?.toInt(), at, duration_ms?.toInt(), due, stability,
        difficulty, reps?.toInt(), lapses?.toInt(),
    )

    override suspend fun unsent(): List<Record> = q.unpushedEvents().awaitAsList().map { it.record() }

    override suspend fun sent(records: List<Record>) {
        val ids = records.filterIsInstance<EventRecord>().map { it.id }
        ids.chunked(500).forEach { q.markPushed(it) }
    }

    /** An event about a card folds its note's cards again; a void, the cards of the note its event was about. */
    override suspend fun keep(record: Record, effects: Effects) {
        if (record !is EventRecord) return
        put(record, pushed = true)
        val voided = if (record.kind == EventRecord.VOID) q.event(record.subjectId).awaitAsOneOrNull() else null
        effects.notes += voided?.subject_id ?: record.subjectId
    }

    /** An event refused stays this device's own: it was marked sent, and there's nothing to show. */
    override suspend fun refused(record: Record, code: String, effects: Effects): String? = null
}
