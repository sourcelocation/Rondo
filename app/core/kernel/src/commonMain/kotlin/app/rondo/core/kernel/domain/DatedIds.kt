package app.rondo.core.kernel.domain

import kotlin.uuid.Uuid

/** UUIDv7s dated from [millis] on, one a millisecond: imports and copies keep their source's order. */
class DatedIds(private var millis: Long) {
    private var n = 0

    /** The next id; [at] dates it instead, with a counter keeping ids of one moment apart. */
    fun next(at: Long? = null): String {
        val time = at ?: millis++
        val random = Uuid.random().toByteArray()
        val bytes = ByteArray(16) { i -> if (i < 6) (time ushr (40 - 8 * i)).toByte() else random[i] }
        val count = if (at != null) n++ and 0xfff else 0
        bytes[6] = (0x70 or ((count ushr 8) and 0x0f)).toByte()
        bytes[7] = count.toByte()
        bytes[8] = (bytes[8].toInt() and 0x3f or 0x80).toByte()
        return Uuid.fromByteArray(bytes).toString()
    }
}
