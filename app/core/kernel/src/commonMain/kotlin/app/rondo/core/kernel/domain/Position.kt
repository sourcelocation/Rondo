package app.rondo.core.kernel.domain

/**
 * A place among siblings, as a fractional index: a key that sorts between its neighbours, so moving
 * one row changes one row. [key] after [after] and before [before]; null means no neighbour there.
 */
class Position(val key: String) {
    constructor(after: String?, before: String?) : this(between(after, before))

    val valid: Boolean get() = VALID.matches(key) && !key.endsWith('0')

    override fun toString(): String = key

    private companion object {
        const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val VALID = Regex("^[0-9A-Za-z]{1,64}$")

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
    }
}
