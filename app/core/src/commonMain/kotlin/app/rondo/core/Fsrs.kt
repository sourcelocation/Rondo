package app.rondo.core

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** Days as the learner lives them: in their time zone, starting at 4 am. */
class Days(timezone: String?, private val startHour: Int = 4) {
    private val zone = timezone?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: TimeZone.currentSystemDefault()

    fun index(at: Long): Long =
        Instant.fromEpochMilliseconds(at - startHour * HOUR).toLocalDateTime(zone).date.toEpochDays()

    fun start(day: Long): Long =
        LocalDateTime(LocalDate.fromEpochDays(day), LocalTime(startHour, 0)).toInstant(zone).toEpochMilliseconds()

    /** The moment [minutes] after midnight on the calendar [date] (in epoch days), in this time zone. */
    fun at(date: Long, minutes: Int): Long {
        val time = LocalTime(minutes / 60, minutes % 60)
        return LocalDateTime(LocalDate.fromEpochDays(date), time).toInstant(zone).toEpochMilliseconds()
    }

    companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}

/** One card's memory for one learner. [due] and [last] are epoch milliseconds. */
data class Memory(
    val state: Int = NEW,
    val step: Int = 0,
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val due: Long? = null,
    val last: Long? = null,
    val reps: Int = 0,
    val lapses: Int = 0,
) {
    val started: Boolean get() = state != NEW && stability > 0

    companion object {
        const val NEW = 0
        const val LEARNING = 1
        const val REVIEW = 2
        const val RELEARNING = 3
    }
}

/**
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
        var next = m.copy(reps = m.reps + 1)
        var interval = 0L
        var days = 0
        fun memory(s: Double, d: Double) {
            next = next.copy(stability = s, difficulty = d)
        }
        fun graduate() {
            next = next.copy(state = Memory.REVIEW, step = -1)
            days = interval(next.stability)
        }
        fun step(steps: LongArray) {
            when (rating) {
                1 -> next = next.copy(step = 0).also { interval = steps[0] }

                2 -> interval = when {
                    next.step == 0 && steps.size == 1 -> steps[0] * 3 / 2
                    next.step == 0 -> (steps[0] + steps[1]) / 2
                    else -> steps[next.step]
                }

                3 -> if (next.step + 1 == steps.size) {
                    graduate()
                } else {
                    next = next.copy(step = next.step + 1)
                    interval = steps[next.step]
                }

                else -> graduate()
            }
        }
        fun updated(): Double = if (sameDay) {
            shortTerm(m.stability, rating)
        } else {
            nextStability(m.difficulty, m.stability, recall(elapsed, m.stability), rating)
        }

        when (m.state) {
            Memory.NEW, Memory.LEARNING -> {
                when {
                    !m.started -> memory(clampS(w[rating - 1]), clampD(initialDifficulty(rating)))
                    else -> memory(updated(), nextDifficulty(m.difficulty, rating))
                }
                next = next.copy(state = Memory.LEARNING)
                if (m.step >= LEARNING.size && rating != 1) graduate() else step(LEARNING)
            }

            Memory.REVIEW -> {
                memory(updated(), nextDifficulty(m.difficulty, rating))
                if (rating == 1) {
                    next = next.copy(lapses = next.lapses + 1, state = Memory.RELEARNING, step = 0)
                    interval = RELEARNING[0]
                } else {
                    days = interval(next.stability)
                }
            }

            else -> {
                memory(updated(), nextDifficulty(m.difficulty, rating))
                if (m.step >= RELEARNING.size && rating != 1) graduate() else step(RELEARNING)
            }
        }
        val due = if (next.state == Memory.REVIEW && days > 0) {
            if (fuzz) days = fuzzed(key, next.reps, days)
            this.days?.let { it.start(it.index(at) + days) } ?: (at + days * Days.DAY)
        } else {
            at + interval
        }
        return next.copy(due = due, last = at)
    }

    private fun elapsed(last: Long, now: Long): Int {
        val days = days ?: return if (now < last) 0 else ((now - last) / Days.DAY).toInt()
        return max(0L, days.index(now) - days.index(last)).toInt()
    }

    internal fun fuzzed(key: String, reps: Int, days: Int): Int {
        if (days < 3) return days
        var delta = 1.0
        for ((start, end, f) in FUZZ) delta += f * max(min(days.toDouble(), end) - start, 0.0)
        val hi = min(round(days + delta).toInt(), MAX_INTERVAL)
        val lo = min(max(2, round(days - delta).toInt()), hi)
        var h = "$key:$reps".hashCode().toLong() * -7046029254386353131L
        h = h xor (h ushr 31)
        val uniform = (h ushr 11).toDouble() / (1L shl 53)
        return min(lo + (uniform * (hi - lo + 1)).toInt(), MAX_INTERVAL)
    }

    /**
     * How surprised these weights are by a history: the log loss of each review's predicted recall
     * (same-day reviews only move memory). Each card is (days since the previous review, rating)
     * pairs, oldest first; the first pair's days are ignored.
     */
    internal fun loss(cards: List<IntArray>): Double {
        var sum = 0.0
        var n = 0
        for (c in cards) {
            var s = clampS(w[c[1] - 1])
            var d = clampD(initialDifficulty(c[1]))
            for (i in 2 until c.size step 2) {
                val rating = c[i + 1]
                if (c[i] == 0) {
                    s = shortTerm(s, rating)
                } else {
                    val p = recall(c[i], s).coerceIn(1e-6, 1 - 1e-6)
                    sum -= if (rating > 1) ln(p) else ln(1 - p)
                    n++
                    s = nextStability(d, s, p, rating)
                }
                d = nextDifficulty(d, rating)
            }
        }
        return sum / max(n, 1)
    }

    private fun clampS(v: Double) = max(v, 0.001)
    private fun clampD(v: Double) = v.coerceIn(1.0, 10.0)
    private fun initialDifficulty(r: Int) = w[4] - exp(w[5] * (r - 1)) + 1

    private fun shortTerm(s: Double, r: Int): Double {
        var increase = exp(w[17] * (r - 3 + w[18])) * s.pow(-w[19])
        if (r != 1) increase = max(increase, 1.0)
        return clampS(s * increase)
    }

    private fun nextDifficulty(d: Double, r: Int): Double {
        val damped = d + (10 - d) * -(w[6] * (r - 3)) / 9
        return clampD(w[7] * initialDifficulty(4) + (1 - w[7]) * damped)
    }

    private fun nextStability(d: Double, s: Double, r: Double, rating: Int): Double = clampS(
        if (rating == 1) {
            min(w[11] * d.pow(-w[12]) * ((s + 1).pow(w[13]) - 1) * exp((1 - r) * w[14]), s / exp(w[17] * w[18]))
        } else {
            val hard = if (rating == 2) w[15] else 1.0
            val easy = if (rating == 4) w[16] else 1.0
            s * (1 + exp(w[8]) * (11 - d) * s.pow(-w[9]) * (exp((1 - r) * w[10]) - 1) * hard * easy)
        },
    )

    companion object {
        const val MAX_INTERVAL = 36_500
        val LEARNING = longArrayOf(60_000, 600_000)
        val RELEARNING = longArrayOf(600_000)
        val DEFAULT = listOf(
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796,
            1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
        )
        val LOWER = doubleArrayOf(
            0.001, 0.001, 0.001, 0.001, 1.0, 0.001, 0.001, 0.001, 0.0, 0.0, 0.001,
            0.001, 0.001, 0.001, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.1,
        )
        val UPPER = doubleArrayOf(
            100.0, 100.0, 100.0, 100.0, 10.0, 4.0, 4.0, 0.75, 4.5, 0.8, 3.5,
            5.0, 0.25, 0.9, 4.0, 1.0, 6.0, 2.0, 2.0, 0.8, 0.8,
        )
        private val FUZZ = listOf(Triple(2.5, 7.0, 0.15), Triple(7.0, 20.0, 0.1), Triple(20.0, 1e18, 0.05))

        fun valid(w: List<Double>): Boolean = w.size == 21 && w.indices.all { w[it] in LOWER[it]..UPPER[it] }

        fun parse(weights: String?): List<Double>? =
            weights?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf(::valid)
    }
}
