package app.rondo.core

import app.rondo.core.model.Note
import kotlin.jvm.JvmInline

/**
 * A note's tags, kept the way Anki keeps them: words without spaces, `::` nesting them
 * ("Cardio::Arrhythmia"), joined by single spaces. Everything that makes tags goes through [of],
 * which also sorts them and drops repeats (case aside); the rules only ask for [isWellFormed].
 */
@JvmInline
value class Tags private constructor(val text: String) {
    val list: List<String> get() = if (text.isEmpty()) emptyList() else text.split(' ')

    fun isEmpty(): Boolean = text.isEmpty()

    operator fun plus(added: Collection<String>): Tags = of(list + added)

    /** Without [removed] (case aside); the tags under them stay. */
    operator fun minus(removed: Collection<String>): Tags {
        val gone = removed.mapNotNull(::clean).map { it.lowercase() }.toSet()
        return of(list.filter { it.lowercase() !in gone })
    }

    /** Whether it has [tag], or a tag under it. */
    fun has(tag: String): Boolean = list.any { under(it, tag) }

    override fun toString(): String = text

    companion object {
        /** How long a tag may be, and how many a note may have. */
        const val MAX_LENGTH = 200
        const val MAX_COUNT = 300

        val NONE = Tags("")

        private val SPACE = Regex("\\s+")

        /** [tags] tidied, without repeats (the first spelling stays) and sorted; too long ones are left out. */
        fun of(tags: Collection<String>): Tags {
            val seen = HashSet<String>()
            val kept = tags.flatMap { it.split(SPACE) }.mapNotNull(::clean)
                .filter { it.length <= MAX_LENGTH && seen.add(it.lowercase()) }
            return Tags(kept.sortedWith(compareBy({ it.lowercase() }, { it })).take(MAX_COUNT).joinToString(" "))
        }

        /** Tags as stored, or as typed: separated by spaces. */
        fun parse(text: String?): Tags = if (text.isNullOrBlank()) NONE else of(listOf(text))

        /** A note's tags; none on one from an app that predates them. */
        fun of(note: Note): Tags = parse(note.tags)

        /** One tag tidied: spaces become underscores, empty levels go ("a::::b" is "a::b"); null if nothing's left. */
        fun clean(tag: String): String? =
            tag.trim().replace(SPACE, "_").split("::").filter { it.isNotEmpty() }.joinToString("::").ifEmpty { null }

        /** Whether [tag] is [parent] or under it, case aside. */
        fun under(tag: String, parent: String): Boolean {
            val p = clean(parent)?.lowercase() ?: return false
            val t = tag.lowercase()
            return t == p || t.startsWith("$p::")
        }

        /** What the rules ask of a note's tags: tidy tags, single spaces, within the limits. */
        fun isWellFormed(text: String): Boolean {
            if (text.isEmpty()) return true
            val tags = text.split(' ')
            return tags.size <= MAX_COUNT && tags.all { it.length <= MAX_LENGTH && clean(it) == it }
        }
    }
}
