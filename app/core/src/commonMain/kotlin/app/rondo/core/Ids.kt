package app.rondo.core

import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Identifiers: UUIDv7, so ordering by id is ordering by creation. */
object Ids {
    fun new(): String = Uuid.generateV7().toString()

    /** A UUIDv7 for [millis]: imports keep their source's order. [n] keeps ids of one millisecond apart. */
    fun at(millis: Long, n: Int = 0): String {
        val random = Uuid.random().toByteArray()
        val bytes = ByteArray(16) { i -> if (i < 6) (millis ushr (40 - 8 * i)).toByte() else random[i] }
        bytes[6] = (0x70 or ((n ushr 8) and 0x0f)).toByte()
        bytes[7] = n.toByte()
        bytes[8] = (bytes[8].toInt() and 0x3f or 0x80).toByte()
        return Uuid.fromByteArray(bytes).toString()
    }

    fun isValid(id: String?): Boolean = id != null && Uuid.parseOrNull(id) != null

    /** The ids of built-in templates: reserved, never stored. */
    fun builtin(n: Int): String = "00000000-0000-7000-8000-" + n.toString().padStart(12, '0')
}

fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * A row's clock for last-writer-wins: milliseconds since 2025 (43 bits), a counter (6 bits) and the
 * device (4 bits) — 53 bits, exact in every runtime's numbers, JavaScript's included. Equal clocks
 * are harmless: the server keeps the first. [observe] keeps it ahead of every clock seen.
 */
class Hlc(private val device: Int, private val now: () -> Long = ::nowMillis) {
    private var last = 0L

    init {
        require(device in 0..DEVICE_MASK)
    }

    fun next(): Long {
        val wall = (now() - EPOCH) shl 10 or device.toLong()
        last = if (wall > last) wall else ((last ushr 4) + 1 shl 4) or device.toLong()
        return last
    }

    fun observe(v: Long) {
        if (v > last) last = v
    }

    companion object {
        const val DEVICE_MASK = 15
        const val EPOCH = 1_735_689_600_000L

        fun millis(v: Long): Long = (v ushr 10) + EPOCH
    }
}

/** Fractional indexes: strings that sort between their neighbours, so moving one row changes one row. */
object Positions {
    private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private val VALID = Regex("^[0-9A-Za-z]{1,64}$")

    fun isValid(key: String): Boolean = VALID.matches(key) && !key.endsWith('0')

    /** A key after [a] and before [b]; null means no neighbour on that side. */
    fun between(a: String?, b: String?): String {
        require(a == null || b == null || a < b) { "$a is not before $b" }
        val out = StringBuilder()
        var open = b == null
        var i = 0
        while (true) {
            val low = a?.getOrNull(i)?.let(DIGITS::indexOf) ?: 0
            val high = if (open) DIGITS.length else DIGITS.indexOf(b!![i])
            val mid = (low + high) / 2
            if (mid > low) return out.append(DIGITS[mid]).toString()
            out.append(DIGITS[low])
            if (high > low) open = true
            i++
        }
    }

    /** [count] evenly spaced short keys, for siblings created together (imports, copies). */
    fun spread(count: Int): List<String> {
        var width = 1
        var space = 62L
        while (space <= count + 1) {
            width++
            space *= 62
        }
        val step = space / (count + 1)
        return (1..count).map { i ->
            var n = i * step
            val key = CharArray(width)
            for (p in width - 1 downTo 0) {
                key[p] = DIGITS[(n % 62).toInt()]
                n /= 62
            }
            key.concatToString().trimEnd('0')
        }
    }
}

/** A short hash of a set of deck ids: a stream's devices and the server compare these. */
object Fingerprint {
    fun of(ids: Collection<String>): String {
        var h = -3750763034362895579L
        for (b in ids.sorted().joinToString(",").encodeToByteArray()) {
            h = (h xor (b.toLong() and 0xff)) * 1099511628211L
        }
        return h.toULong().toString(16)
    }
}
