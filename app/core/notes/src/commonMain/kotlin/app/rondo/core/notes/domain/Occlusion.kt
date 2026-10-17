package app.rondo.core.notes.domain

/**
 * An image occlusion field: the image's hash on the first line, then one mask per line,
 * `group x y w h` in fractions of the image. [masks] is null when the text isn't one.
 */
class Occlusion(val text: String) {
    class Rect(val group: Int, val x: Double, val y: Double, val w: Double, val h: Double)

    /** An image with [masks]. */
    constructor(image: String, masks: List<Rect>) : this(
        (listOf(image) + masks.map { "${it.group} ${round4(it.x)} ${round4(it.y)} ${round4(it.w)} ${round4(it.h)}" })
            .joinToString("\n"),
    )

    val image: String get() = text.substringBefore('\n').trim()

    val masks: List<Rect>? get() {
        val lines = text.split('\n')
        if (!Template.HASH.matches(lines.first().trim())) return null
        return lines.drop(1).filter { it.isNotBlank() }.map { line ->
            val p = line.trim().split(' ')
            if (p.size != 5) return null
            val n = p.drop(1).map { it.toDoubleOrNull()?.takeIf { v -> v in 0.0..1.0 } ?: return null }
            Rect(p[0].toIntOrNull()?.takeIf { it in 1..999 } ?: return null, n[0], n[1], n[2], n[3])
        }
    }

    private companion object {
        fun round4(v: Double): String = (kotlin.math.round(v * 10_000) / 10_000).toString()
    }
}
