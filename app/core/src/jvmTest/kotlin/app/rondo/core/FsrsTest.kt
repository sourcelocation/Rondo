package app.rondo.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Replays py-fsrs 6's reference outputs (fsrs6.json, generated without fuzz, 24-hour days). */
class FsrsTest {
    private val fixture = Json.parseToJsonElement(javaClass.getResource("/fsrs6.json")!!.readText()).jsonObject
    private val states = listOf("new", "learning", "review", "relearning")

    private fun close(expected: Double, actual: Double) =
        assertTrue(abs(expected - actual) < 1e-6, "expected $expected, got $actual")

    @Test
    fun scenarios() {
        var replayed = 0
        for (scenario in fixture.getValue("scenarios").jsonArray.map { it.jsonObject }) {
            val config = scenario.getValue("config").jsonObject
            val steps = config.getValue("learning_steps_s").jsonArray.map { it.jsonPrimitive.int * 1000L }
            if (steps != Fsrs.LEARNING.toList() ||
                config.getValue("maximum_interval").jsonPrimitive.int != Fsrs.MAX_INTERVAL
            ) {
                continue
            }
            val weights = config.getValue("parameters").jsonArray.map { it.jsonPrimitive.double }
            val fsrs = Fsrs(weights, config.getValue("desired_retention").jsonPrimitive.double, fuzz = false)
            var memory = Memory()
            for (review in scenario.getValue("reviews").jsonArray.map { it.jsonObject }) {
                memory = fsrs.review("card", memory, review.int("rating"), review.time("at"))
                val name = scenario.getValue("name").jsonPrimitive.content
                assertEquals(review.getValue("state").jsonPrimitive.content, states[memory.state], name)
                close(review.getValue("stability").jsonPrimitive.double, memory.stability)
                close(review.getValue("difficulty").jsonPrimitive.double, memory.difficulty)
                assertEquals(review.time("due"), memory.due, name)
            }
            replayed++
        }
        assertTrue(replayed >= 9)
    }

    @Test
    fun retrievabilityAndIntervals() {
        for (r in fixture.getValue("retrievability").jsonArray.map { it.jsonObject }) {
            val memory =
                Memory(
                    Memory.REVIEW,
                    stability = r.getValue("stability").jsonPrimitive.double,
                    difficulty = 5.0,
                    last = 0,
                )
            close(
                r.getValue("r").jsonPrimitive.double,
                Fsrs(fuzz = false).retrievability(
                    memory,
                    r.int("elapsed_days") * Days.DAY,
                ),
            )
        }
        for (i in fixture.getValue("intervals").jsonArray.map { it.jsonObject }) {
            val fsrs = Fsrs(retention = i.getValue("desired_retention").jsonPrimitive.double)
            assertEquals(i.int("interval_days"), fsrs.interval(i.getValue("stability").jsonPrimitive.double))
        }
    }

    @Test
    fun fuzzStaysInTheReferenceRanges() {
        for (f in fixture.getValue("fuzz_ranges").jsonArray.map { it.jsonObject }) {
            val got = (0 until 200).map { Fsrs().fuzzed("card$it", 3, f.int("interval_days")) }.toSet()
            assertTrue(got.all { it in f.int("min")..f.int("max") }, "$f: $got")
            assertEquals(f.int("min") != f.int("max"), got.size > 1)
        }
    }

    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int

    private fun JsonObject.time(key: String) = Instant.parse(getValue(key).jsonPrimitive.content).toEpochMilliseconds()
}
