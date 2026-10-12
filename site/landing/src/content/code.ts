/**
 * Rondo's own source, under the open-source section's lens: excerpts of app/core, copied as they stood rather than
 * read at build time, so the site image needs nothing outside site/ and brand/. They are a texture, not a reference,
 * and don't have to follow later changes.
 */
export interface Excerpt {
  readonly file: string;
  readonly code: string;
}

export const excerpts: readonly Excerpt[] = [
  {
    file: "app/core/Fsrs.kt",
    code: `/**
 * FSRS-6, ported from the reference implementation (py-fsrs 6) and checked against its outputs.
 * [days] null counts days as 24-hour periods (the reference behaviour); otherwise calendar days.
 */
class Fsrs(
    weights: List<Double>? = null,
    private val retention: Double = 0.9,
    private val days: Days? = null,
    private val fuzz: Boolean = true,
) {
    private val w: DoubleArray = (weights?.takeIf(::valid) ?: DEFAULT).toDoubleArray()

    /** The weights in use: those given when they're within FSRS's bounds, otherwise the defaults. */
    val weights: List<Double> get() = w.toList()
    private val decay = -w[20]
    private val factor = 0.9.pow(1 / decay) - 1

    fun retrievability(m: Memory, at: Long): Double {
        if (!m.started || m.last == null) return 0.0
        return recall(elapsed(m.last, at), m.stability)
    }

    private fun recall(elapsedDays: Int, stability: Double) = (1 + factor * max(elapsedDays, 0) / stability).pow(decay)

    fun interval(stability: Double): Int =
        round(stability / factor * (retention.pow(1 / decay) - 1)).toInt().coerceIn(1, MAX_INTERVAL)

    /** The memory after answering [rating] (1 Again … 4 Easy) at [at]. [key] spreads intervals. */
    fun review(key: String, m: Memory, rating: Int, at: Long): Memory {
        val elapsed = m.last?.let { elapsed(it, at) } ?: -1
        val sameDay = elapsed == 0
        var next = m.copy(reps = m.reps + 1)`,
  },
  {
    file: "app/core/Card.kt",
    code: `/**
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

                is Event.Flag -> s.copy(flag = e.color)`,
  },
  {
    file: "app/core/Deck.kt",
    code: `    /** Why [by] may not make this the deck that was [stored] (null: a new one), or null. */
    fun check(by: Access, stored: Deck?): ErrorCode? {
        if (Uuid.parseOrNull(id) == null) return ErrorCode.INVALID
        if (name.isBlank() || name.length > 100 || !Position(position).valid || color !in 0..7 ||
            (icon?.length ?: 0) > 16 || (language?.length ?: 0) > 35 || (description?.length ?: 0) > 2000 ||
            newPerDay !in NEW_PER_DAY || reviewsPerDay !in REVIEWS_PER_DAY || retention !in RETENTION ||
            unlockAt !in 50..100 || unlockAt % 10 != 0
        ) {
            return ErrorCode.INVALID
        }
        if (stored != null) {
            if (stored.ownerId == null) return ErrorCode.READ_ONLY
            if (stored.ownerId != ownerId) return ErrorCode.OWNER_MISMATCH
            by.canWrite(stored.id)?.let { return it }
            // A borrower can't move or delete the deck lent to them, nor take a deck out of it.
            val borrowed = stored.ownerId != by.actor
            val movedOrDeleted = parentId != stored.parentId || deletedAt != null
            if (borrowed && stored.id in by.lends && movedOrDeleted) return ErrorCode.FORBIDDEN
            if (borrowed && parentId == null && stored.parentId != null) return ErrorCode.FORBIDDEN
        } else if (parentId == null) {
            if (ownerId != by.actor) return ErrorCode.OWNER_MISMATCH
        }
        if (parentId != null && (stored == null || parentId != stored.parentId)) {
            val parent = by.tree[parentId] ?: return ErrorCode.NOT_FOUND
            if (parent.ownerId != ownerId) return ErrorCode.OWNER_MISMATCH
            if (by.tree.dead(parent.id)) return ErrorCode.NOT_FOUND
            by.canWrite(parent.id)?.let { return it }
            val above = listOf(parent) + by.tree.ancestors(parent.id)
            if (above.any { it.id == id }) return ErrorCode.CYCLE
            if (above.size >= 32) return ErrorCode.INVALID
        }
        return null
    }`,
  },
];
