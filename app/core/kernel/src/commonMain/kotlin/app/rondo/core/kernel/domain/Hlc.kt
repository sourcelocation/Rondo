package app.rondo.core.kernel.domain

import kotlin.time.Clock

/**
 * A row's clock for last-writer-wins: milliseconds since 2025 (43 bits), a counter (6 bits) and the
 * device (4 bits) — 53 bits, exact in every runtime's numbers, JavaScript's included. Equal clocks
 * are harmless: the server keeps the first. [observe] keeps it ahead of every clock seen.
 */
class Hlc(private val device: Int, private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
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

    /** Whether [v] is from further ahead than any device's clock may be. */
    fun ahead(v: Long): Boolean = (v ushr 10) + EPOCH > now() + SKEW

    companion object {
        const val DEVICE_MASK = 15
        const val EPOCH = 1_735_689_600_000L

        /** How far a device's clock may run ahead of the server's. */
        const val SKEW = 10 * 60_000L
    }
}
