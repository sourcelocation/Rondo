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
    file: "app/core/Learning.kt",
    code: `object Learning {
    const val REVIEW = 1
    const val RESET = 2
    const val SUSPEND = 3
    const val UNSUSPEND = 4
    const val BURY = 5
    const val FLAG = 6
    const val SNAPSHOT = 7
    const val VOID = 8
    const val UNLOCK = 9

    /** Folds one card's events (any order) into its state. Stored due dates win over replayed ones. */
    fun fold(key: String, events: List<Event>, fsrs: Fsrs): CardState {
        val voided = events.filter { it.kind == VOID }.map { it.subjectId }.toSet()
        var s = CardState()
        for (e in events.filter { it.id !in voided && it.kind != VOID }.sortedWith(compareBy({ it.at }, { it.id }))) {
            s = when (e.kind) {
                REVIEW -> {
                    val replayed = fsrs.review(key, s.memory, e.value ?: 3, e.at)
                    val next = e.due?.let { replayed.copy(due = it) } ?: replayed
                    s.copy(
                        memory = next,
                        learnedAt = s.learnedAt ?: e.at.takeIf { next.state == Memory.REVIEW },
                        introducedAt = s.introducedAt ?: e.at,
                    )
                }

                RESET -> s.copy(memory = Memory(), learnedAt = null, introducedAt = null)

                SUSPEND -> s.copy(suspended = true)

                UNSUSPEND -> s.copy(suspended = false)

                BURY -> s.copy(buriedAt = e.at)`,
  },
  {
    file: "app/core/Rules.kt",
    code: `/** The rules every write must pass, on devices before writing and on the server before storing. */
object Rules {
    private const val CLOCK_SKEW = 10 * 60_000L

    fun clockAhead(v: Long, now: Long): Boolean = Hlc.millis(v) > now + CLOCK_SKEW

    fun deck(a: Access, stored: Deck?, d: Deck): ErrorCode? {
        if (!Ids.isValid(d.id)) return ErrorCode.INVALID
        if (d.name.isBlank() || d.name.length > 100 || !Positions.isValid(d.position) || d.color !in 0..7 ||
            (d.icon?.length ?: 0) > 16 || (d.language?.length ?: 0) > 35 || (d.description?.length ?: 0) > 2000 ||
            d.newPerDay !in 0..9999 || d.reviewsPerDay !in 0..99999 || d.retention !in 70..99 ||
            d.unlockAt !in 50..100 || d.unlockAt % 10 != 0
        ) {
            return ErrorCode.INVALID
        }
        if (stored != null) {
            if (stored.ownerId == null) return ErrorCode.READ_ONLY
            if (stored.ownerId != d.ownerId) return ErrorCode.OWNER_MISMATCH
            a.canWrite(stored.id)?.let { return it }
            // A borrower can't move or delete the deck lent to them, nor take a deck out of it.
            val borrowed = stored.ownerId != a.actor
            val movedOrDeleted = d.parentId != stored.parentId || d.deletedAt != null
            if (borrowed && stored.id in a.lends && movedOrDeleted) return ErrorCode.FORBIDDEN
            if (borrowed && d.parentId == null && stored.parentId != null) return ErrorCode.FORBIDDEN
        } else if (d.parentId == null) {
            if (d.ownerId != a.actor) return ErrorCode.OWNER_MISMATCH
        }
        if (d.parentId != null && (stored == null || d.parentId != stored.parentId)) {
            val parent = a.tree[d.parentId] ?: return ErrorCode.NOT_FOUND
            if (parent.ownerId != d.ownerId) return ErrorCode.OWNER_MISMATCH
            if (a.tree.dead(parent.id)) return ErrorCode.NOT_FOUND
            a.canWrite(parent.id)?.let { return it }
            val above = listOf(parent) + a.tree.ancestors(parent.id)
            if (above.any { it.id == d.id }) return ErrorCode.CYCLE
            if (above.size >= 32) return ErrorCode.INVALID
        }
        return null
    }`,
  },
];
