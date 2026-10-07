package app.rondo.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OptimizerTest {
    private fun <T> run(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
        return result!!.getOrThrow()
    }

    /** Reviews of a learner whose memory works by [truth]: each due after about its stability. */
    private fun history(truth: List<Double>, cards: Int): List<IntArray> {
        val fsrs = Fsrs(truth, fuzz = false)
        val random = Random(1)
        return List(cards) { card ->
            var m = Memory()
            var at = 0L
            var previous = 0L
            IntArray(12).also { pairs ->
                for (k in 0 until 6) {
                    val rating = when {
                        k == 0 -> random.nextInt(1, 5)
                        random.nextDouble() < fsrs.retrievability(m, at) -> 3
                        else -> 1
                    }
                    pairs[2 * k] = ((at - previous) / Days.DAY).toInt()
                    pairs[2 * k + 1] = rating
                    m = fsrs.review("c$card", m, rating, at)
                    previous = at
                    at += max(1L, (m.stability * random.nextDouble(0.5, 1.5)).toLong()) * Days.DAY
                }
            }
        }
    }

    @Test
    fun learnsWeightsThatPredictTheLearnerBetter() {
        val truth = Fsrs.DEFAULT.mapIndexed { i, w ->
            when {
                i < 4 -> w * 3
                i == 8 -> 1.2
                else -> w
            }
        }
        val cards = history(truth, 400)
        val fitted = assertNotNull(run { Optimizer.fit(cards, steps = 40) })
        assertTrue(Fsrs.valid(fitted))
        val before = Fsrs(Fsrs.DEFAULT).loss(cards)
        val after = Fsrs(fitted).loss(cards)
        assertTrue(after < before, "loss $before -> $after")
        assertTrue(after - Fsrs(truth).loss(cards) < (before - Fsrs(truth).loss(cards)) / 2, "closer to the truth")
    }

    @Test
    fun waitsForEnoughReviews() {
        assertNull(run { Optimizer.fit(history(Fsrs.DEFAULT, 50)) })
    }
}
