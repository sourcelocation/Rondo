package app.rondo.core.study.infrastructure

import app.rondo.core.kernel.infrastructure.Record
import app.rondo.core.study.domain.Event
import app.rondo.core.study.domain.Memory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** An event: [kind] 1 review, 2 reset, 3 suspend, 4 unsuspend, 5 bury, 6 flag, 7 snapshot, 8 void, 9 unlock. */
@Serializable
@SerialName("event")
class EventRecord(
    override val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("subject_id") val subjectId: String,
    val card: Int,
    val kind: Int,
    val value: Int? = null,
    val at: Long,
    @SerialName("duration_ms") val durationMs: Int? = null,
    val due: Long? = null,
    val stability: Double? = null,
    val difficulty: Double? = null,
    val reps: Int? = null,
    val lapses: Int? = null,
) : Record() {
    override val v: Long? get() = null

    constructor(e: Event) : this(
        e.id, e.userId, e.subjectId, e.card,
        kind = when (e) {
            is Event.Review -> REVIEW
            is Event.Reset -> RESET
            is Event.Suspend -> SUSPEND
            is Event.Unsuspend -> UNSUSPEND
            is Event.Bury -> BURY
            is Event.Flag -> FLAG
            is Event.Snapshot -> SNAPSHOT
            is Event.Void -> VOID
            is Event.Unlock -> UNLOCK
        },
        value = when (e) {
            is Event.Review -> e.rating
            is Event.Flag -> e.color
            is Event.Snapshot -> e.memory.state
            else -> null
        },
        at = e.at,
        durationMs = (e as? Event.Review)?.durationMs,
        due = (e as? Event.Review)?.due ?: (e as? Event.Snapshot)?.memory?.due,
        stability = (e as? Event.Snapshot)?.memory?.stability,
        difficulty = (e as? Event.Snapshot)?.memory?.difficulty,
        reps = (e as? Event.Snapshot)?.memory?.reps,
        lapses = (e as? Event.Snapshot)?.memory?.lapses,
    )

    /** The event it stores; null for a kind this app doesn't know. */
    fun event(): Event? = when (kind) {
        REVIEW -> Event.Review(id, userId, subjectId, card, at, value ?: 3, durationMs, due)

        RESET -> Event.Reset(id, userId, subjectId, card, at)

        SUSPEND -> Event.Suspend(id, userId, subjectId, card, at)

        UNSUSPEND -> Event.Unsuspend(id, userId, subjectId, card, at)

        BURY -> Event.Bury(id, userId, subjectId, card, at)

        FLAG -> Event.Flag(id, userId, subjectId, card, at, value ?: 0)

        SNAPSHOT -> Event.Snapshot(
            id,
            userId,
            subjectId,
            card,
            Memory(
                state = value ?: Memory.NEW,
                stability = stability ?: 0.0,
                difficulty = difficulty ?: 0.0,
                due = due,
                last = at,
                reps = reps ?: 0,
                lapses = lapses ?: 0,
            ),
        )

        VOID -> Event.Void(id, userId, subjectId, at)

        UNLOCK -> Event.Unlock(id, userId, subjectId, at)

        else -> null
    }

    companion object {
        const val REVIEW = 1
        const val RESET = 2
        const val SUSPEND = 3
        const val UNSUSPEND = 4
        const val BURY = 5
        const val FLAG = 6
        const val SNAPSHOT = 7
        const val VOID = 8
        const val UNLOCK = 9
    }
}
