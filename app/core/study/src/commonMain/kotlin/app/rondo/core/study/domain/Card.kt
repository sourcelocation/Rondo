package app.rondo.core.study.domain

import kotlin.uuid.Uuid

/**
 * One card of a note, for one learner: card [number] of note [noteId], and its events. Where it
 * stands is never stored on the server; it's folded from them ([state]).
 */
class Card(val noteId: String, val number: Int, val events: List<Event>) {
    /**
     * Where the card stands: its events (any order) replayed. A voided event counts for nothing,
     * and a due date that was stored wins over a replayed one.
     */
    fun state(fsrs: Fsrs): CardState {
        val key = "$noteId:$number"
        val voided = events.filterIsInstance<Event.Void>().map { it.subjectId }.toSet()
        var s = CardState()
        for (e in events.filter { it.id !in voided && it !is Event.Void }.sortedWith(compareBy({ it.at }, { it.id }))) {
            s = when (e) {
                is Event.Review -> {
                    val replayed = fsrs.review(key, s.memory, e.rating, e.at)
                    val next = e.due?.let { replayed.copy(due = it) } ?: replayed
                    s.copy(
                        memory = next,
                        learnedAt = s.learnedAt ?: e.at.takeIf { next.state == Memory.REVIEW },
                        introducedAt = s.introducedAt ?: e.at,
                    )
                }

                is Event.Reset -> s.copy(memory = Memory(), learnedAt = null, introducedAt = null)

                is Event.Suspend -> s.copy(suspended = true)

                is Event.Unsuspend -> s.copy(suspended = false)

                is Event.Bury -> s.copy(buriedAt = e.at)

                is Event.Flag -> s.copy(flag = e.color)

                is Event.Snapshot -> {
                    val learned = e.memory.state == Memory.REVIEW || e.memory.state == Memory.RELEARNING
                    // Reviews before the snapshot (an import with its history) keep when the card started.
                    s.copy(
                        memory = e.memory,
                        learnedAt = if (learned) s.learnedAt ?: e.at else null,
                        introducedAt = if (e.memory.state != Memory.NEW) s.introducedAt ?: e.at else null,
                    )
                }

                is Event.Void, is Event.Unlock -> s
            }
        }
        return s
    }
}

/** One card's state for one learner, folded from their events. */
data class CardState(
    val memory: Memory = Memory(),
    /** First time the card left its learning steps; never undone except by a reset. */
    val learnedAt: Long? = null,
    /** First review: when the card stopped being new. */
    val introducedAt: Long? = null,
    val suspended: Boolean = false,
    val buriedAt: Long? = null,
    val flag: Int = 0,
) {
    /**
     * Where card [card] of note [noteId] stands, as [by]'s snapshot: progress for a copy, without the
     * reviews that led there. Null for a new card, which needs none.
     */
    fun snapshot(noteId: String, card: Int, by: String, at: Long): Event.Snapshot? {
        if (memory.state == Memory.NEW) return null
        return Event.Snapshot(Uuid.generateV7().toString(), by, noteId, card, memory.copy(last = memory.last ?: at))
    }
}
