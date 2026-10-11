package app.rondo.core

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

/**
 * Something that happened to a card, or to a deck: what a learner's history is made of. Events are
 * only ever added. [subjectId] is the card's note, an unlock's deck, or a void's event.
 */
sealed class Event(
    val id: String,
    val userId: String,
    val subjectId: String,
    /** The card's number; 0 for an unlock or a void. */
    val card: Int,
    val at: Long,
) {
    /** The same event under another [id], for another learner or about another subject: a copy's. */
    abstract fun with(id: String, userId: String, subjectId: String): Event

    /** An answer: [rating] 1 to 4, how long it took, and the due date it gave. */
    class Review(
        id: String,
        userId: String,
        subjectId: String,
        card: Int,
        at: Long,
        val rating: Int,
        val durationMs: Int? = null,
        val due: Long? = null,
    ) : Event(id, userId, subjectId, card, at) {
        override fun with(id: String, userId: String, subjectId: String) =
            Review(id, userId, subjectId, card, at, rating, durationMs, due)
    }

    /** The card starts over, new. */
    class Reset(id: String, userId: String, subjectId: String, card: Int, at: Long) :
        Event(id, userId, subjectId, card, at) {
        override fun with(id: String, userId: String, subjectId: String) = Reset(id, userId, subjectId, card, at)
    }

    class Suspend(id: String, userId: String, subjectId: String, card: Int, at: Long) :
        Event(id, userId, subjectId, card, at) {
        override fun with(id: String, userId: String, subjectId: String) = Suspend(id, userId, subjectId, card, at)
    }

    class Unsuspend(id: String, userId: String, subjectId: String, card: Int, at: Long) :
        Event(id, userId, subjectId, card, at) {
        override fun with(id: String, userId: String, subjectId: String) = Unsuspend(id, userId, subjectId, card, at)
    }

    /** Not today: back tomorrow. */
    class Bury(id: String, userId: String, subjectId: String, card: Int, at: Long) :
        Event(id, userId, subjectId, card, at) {
        override fun with(id: String, userId: String, subjectId: String) = Bury(id, userId, subjectId, card, at)
    }

    /** A flag's [color], 1 to 7; 0 takes it off. */
    class Flag(id: String, userId: String, subjectId: String, card: Int, at: Long, val color: Int) :
        Event(id, userId, subjectId, card, at) {
        override fun with(id: String, userId: String, subjectId: String) = Flag(id, userId, subjectId, card, at, color)
    }

    /** Where the card stands, without the reviews that led there: an import's, or a copy's. */
    class Snapshot(id: String, userId: String, subjectId: String, card: Int, val memory: Memory) :
        Event(id, userId, subjectId, card, memory.last ?: 0) {
        override fun with(id: String, userId: String, subjectId: String) = Snapshot(id, userId, subjectId, card, memory)
    }

    /** Takes back the event [subjectId]: an answer undone after it was pushed. */
    class Void(id: String, userId: String, subjectId: String, at: Long) : Event(id, userId, subjectId, 0, at) {
        override fun with(id: String, userId: String, subjectId: String) = Void(id, userId, subjectId, at)
    }

    /** A level opened by hand: [subjectId] is the deck. */
    class Unlock(id: String, userId: String, subjectId: String, at: Long) : Event(id, userId, subjectId, 0, at) {
        override fun with(id: String, userId: String, subjectId: String) = Unlock(id, userId, subjectId, at)
    }

    /** Why [by] may not add this one, now [now], or null. */
    fun check(by: Access, now: Long): ErrorCode? = when {
        userId != by.actor -> ErrorCode.FORBIDDEN
        Uuid.parseOrNull(id) == null || Uuid.parseOrNull(subjectId) == null -> ErrorCode.INVALID
        card !in 0..999 -> ErrorCode.INVALID
        this is Review && rating !in 1..4 -> ErrorCode.INVALID
        this is Flag && color !in 0..7 -> ErrorCode.INVALID
        at > now + Hlc.SKEW -> ErrorCode.CLOCK_AHEAD
        else -> null
    }
}

/**
 * A learner's events by what they're about: a note's, a card's or a deck's, each with the voids
 * that take one of them back (a void is about the event it voids).
 */
class History(events: Collection<Event>) {
    private val voids = events.filterIsInstance<Event.Void>().groupBy { it.subjectId }
    private val about = events.filter { it !is Event.Void }.groupBy { it.subjectId }

    /** The events about [subject], and the voids of any of them. */
    fun of(subject: String): List<Event> = about[subject].orEmpty().let { own ->
        own +
            own.flatMap { voids[it.id].orEmpty() }
    }

    /** Card [card] of note [note], with its events. */
    fun card(note: String, card: Int): Card {
        val own = about[note].orEmpty().filter { it.card == card }
        return Card(note, card, own + own.flatMap { voids[it.id].orEmpty() })
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
