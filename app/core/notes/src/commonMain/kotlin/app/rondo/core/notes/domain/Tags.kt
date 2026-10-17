package app.rondo.core.notes.domain

/**
 * A note's tags, kept the way Anki keeps them: words without spaces, `::` nesting them
 * ("Cardio::Arrhythmia"), joined by single spaces, sorted and without repeats (case aside).
 */
class Tags(tags: Collection<String>) {
    /** As stored, or as typed: separated by spaces. */
    constructor(text: String?) : this(if (text.isNullOrBlank()) emptyList() else listOf(text))

    val text: String = run {
        val seen = HashSet<String>()
        val kept = tags.flatMap { it.split(SPACE) }.mapNotNull { Tag(it).text }
            .filter { it.length <= MAX_LENGTH && seen.add(it.lowercase()) }
        kept.sortedWith(compareBy({ it.lowercase() }, { it })).take(MAX_COUNT).joinToString(" ")
    }

    val list: List<String> get() = if (text.isEmpty()) emptyList() else text.split(' ')

    fun isEmpty(): Boolean = text.isEmpty()

    operator fun plus(added: Collection<String>): Tags = Tags(list + added)

    /** Without [removed] (case aside); the tags under them stay. */
    operator fun minus(removed: Collection<String>): Tags {
        val gone = removed.mapNotNull { Tag(it).text }.map { it.lowercase() }.toSet()
        return Tags(list.filter { it.lowercase() !in gone })
    }

    /** Whether it has [tag], or a tag under it. */
    fun has(tag: String): Boolean = list.any { Tag(it).under(tag) }

    override fun equals(other: Any?): Boolean = other is Tags && other.text == text

    override fun hashCode(): Int = text.hashCode()

    override fun toString(): String = text

    companion object {
        /** How long a tag may be, and how many a note may have. */
        const val MAX_LENGTH = 200
        const val MAX_COUNT = 300

        private val SPACE = Regex("\\s+")

        val NONE = Tags(emptyList())
    }
}

/** One tag, tidied: spaces become underscores, empty levels go ("a::::b" is "a::b"); [text] null if nothing's left. */
class Tag(raw: String) {
    val text: String? = raw.trim().replace(SPACE, "_").split("::").filter { it.isNotEmpty() }.joinToString("::")
        .ifEmpty { null }

    /** Whether it is [parent] or under it, case aside. */
    fun under(parent: String): Boolean {
        val p = Tag(parent).text?.lowercase() ?: return false
        val t = text?.lowercase() ?: return false
        return t == p || t.startsWith("$p::")
    }

    private companion object {
        val SPACE = Regex("\\s+")
    }
}
