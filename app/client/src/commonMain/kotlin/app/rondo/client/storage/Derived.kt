package app.rondo.client.storage

import okio.ByteString.Companion.encodeUtf8

/**
 * Ids made from others, for what an import or a copy adds: [of] keeps a source id's time, so the
 * order, and makes the rest from the source's own and [key]. A source under the same key always
 * gets the same id, so what was added before is found by its [tail], whatever its time.
 */
class Derived(private val key: String) {
    fun of(source: String): String = make(source.take(13), tail(source))

    /** An id dated [millis], the rest made from [key] alone. */
    fun at(millis: Long): String {
        val t = millis.coerceAtLeast(0).toString(16).padStart(12, '0').takeLast(12)
        return make("${t.take(8)}-${t.drop(8)}", "")
    }

    /** All of an id but its time. */
    fun tail(id: String): String = id.substring(14)

    private fun make(time: String, from: String): String {
        val h = "$from/$key".encodeUtf8().sha256().hex()
        val variant = "89ab"[h[3].digitToInt(16) % 4]
        return "$time-7${h.substring(0, 3)}-$variant${h.substring(4, 7)}-${h.substring(7, 19)}"
    }
}
