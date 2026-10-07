package app.rondo.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Fits FSRS weights to one learner's reviews, on their device: Adam over central-difference
 * gradients of [Fsrs.loss], kept within FSRS's bounds. Cards are as [Fsrs.loss] takes them.
 */
object Optimizer {
    /** Fewer reviews than this say more about chance than about the learner. */
    const val MIN_REVIEWS = 1000

    /**
     * Weights that predict [cards] better than [start], or null. [pause] runs between steps, to
     * yield where everything shares one thread.
     */
    suspend fun fit(
        cards: List<IntArray>,
        start: List<Double> = Fsrs.DEFAULT,
        steps: Int = 80,
        pause: suspend () -> Unit = {},
    ): List<Double>? {
        if (cards.sumOf { it.size / 2 - 1 } < MIN_REVIEWS) return null
        // About 20,000 reviews are plenty, and keep a step quick enough for a phone.
        val sample = ArrayList<IntArray>()
        var reviews = 0
        for (c in cards.shuffled(Random(7))) {
            if (reviews >= 20_000) break
            sample += c
            reviews += c.size / 2
        }
        fun loss(w: DoubleArray) = Fsrs(w.toList(), fuzz = false).loss(sample)
        val w = start.toDoubleArray()
        // Steps in proportion to each weight, so initial stabilities (up to 100) move as fast as the rest.
        val scale = DoubleArray(w.size) { max(1.0, abs(w[it])) }
        val m = DoubleArray(w.size)
        val v = DoubleArray(w.size)
        repeat(steps) { t ->
            for (i in w.indices) {
                val h = 1e-4 * max(1.0, abs(w[i]))
                val up = w.copyOf().also { it[i] += h }
                val down = w.copyOf().also { it[i] -= h }
                val g = (loss(up) - loss(down)) / (2 * h)
                m[i] = 0.9 * m[i] + 0.1 * g
                v[i] = 0.999 * v[i] + 0.001 * g * g
            }
            for (i in w.indices) {
                val mHat = m[i] / (1 - 0.9.pow(t + 1))
                val vHat = v[i] / (1 - 0.999.pow(t + 1))
                w[i] = (w[i] - 0.04 * scale[i] * mHat / (sqrt(vHat) + 1e-8)).coerceIn(Fsrs.LOWER[i], Fsrs.UPPER[i])
            }
            pause()
        }
        return w.map { round(it * 10_000) / 10_000 }.takeIf { loss(it.toDoubleArray()) < loss(start.toDoubleArray()) }
    }
}
