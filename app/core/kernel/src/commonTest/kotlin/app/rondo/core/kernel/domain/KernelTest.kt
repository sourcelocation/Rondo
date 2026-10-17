package app.rondo.core.kernel.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

/** Clocks, places among siblings and dated ids: what every slice stamps its rows with. */
class KernelTest {
    @Test
    fun clocksArePackedAndOrdered() {
        var now = Hlc.EPOCH + 5_000
        val a = Hlc(1) { now }
        val b = Hlc(2) { now }
        val first = a.next()
        val second = a.next()
        b.observe(second)
        assertEquals(true, second > first && b.next() > second)
        assertEquals(Hlc.EPOCH + 5_000, (first ushr 10) + Hlc.EPOCH)
        now -= 1_000
        assertEquals(true, a.next() > second)
        val year2300 = Hlc(15) { 10_413_792_000_000 }.next()
        assertEquals(true, year2300 < (1L shl 53))
        val server = Hlc(0) { Clock.System.now().toEpochMilliseconds() }
        assertEquals(true, server.ahead(Hlc(0) { 9_999_999_999_999 }.next()))
    }

    @Test
    fun positions() {
        val keys = mutableListOf<String>()
        val random = kotlin.random.Random(1)
        repeat(300) {
            val i = random.nextInt(keys.size + 1)
            keys.add(i, Position(keys.getOrNull(i - 1), keys.getOrNull(i)).key)
        }
        assertEquals(keys.sorted(), keys)
        assertEquals(true, keys.all { Position(it).valid })
        val between = Position("V", "W").key
        assertEquals(true, between > "V" && between < "W" && Position(between).valid)
    }

    @Test
    fun datedIdsKeepTheirOrder() {
        val ids = DatedIds(1_700_000_000_000)
        val made = List(5) { ids.next() }
        assertEquals(made.sorted(), made)
        val sameMoment = List(3) { ids.next(at = 1_600_000_000_000) }
        assertEquals(sameMoment.sorted(), sameMoment)
    }
}
