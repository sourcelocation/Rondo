package app.rondo.core

import kotlin.uuid.Uuid

/** A note: its fields' text by field id, in a deck, of a note type; its cards come from the type. */
data class Note(
    val id: String,
    val deckId: String,
    val templateId: String,
    /** Field id to content: markup for text, a hash for media, an [Occlusion] for masks. */
    val fields: Map<String, String>,
    val v: Long,
    val ownerId: String? = null,
    val deletedAt: Long? = null,
    /** As [Tags] keeps them: sorted, single spaces. */
    val tags: String = "",
) {
    /** Why [by] may not make this the note that was [stored] (null: a new one) in [template], or null. */
    fun check(by: Access, stored: Note?, template: Template?): ErrorCode? {
        if (Uuid.parseOrNull(id) == null) return ErrorCode.INVALID
        if (!tagsWellFormed) return ErrorCode.INVALID
        if (stored != null) {
            if (stored.ownerId == null) return ErrorCode.READ_ONLY
            if (stored.ownerId != ownerId) return ErrorCode.OWNER_MISMATCH
            by.canWrite(stored.deckId)?.let { return it }
        }
        val deck = by.tree[deckId] ?: return ErrorCode.NOT_FOUND
        if (by.tree.dead(deck.id) && deletedAt == null) return ErrorCode.NOT_FOUND
        if (deck.ownerId != ownerId) {
            return if (deck.ownerId == null) ErrorCode.READ_ONLY else ErrorCode.OWNER_MISMATCH
        }
        by.canWrite(deck.id)?.let { return it }
        template ?: return ErrorCode.NOT_FOUND
        if (!template.builtin && template.ownerId != ownerId) return ErrorCode.OWNER_MISMATCH
        // Anki's notes (kept 1:1) are read-only: neither their fields nor their note type change.
        val changed = stored != null && (stored.fields != fields || stored.templateId != templateId)
        if (template is AnkiTemplate && changed) return ErrorCode.READ_ONLY
        if (template.problem(fields) != null) return ErrorCode.INVALID
        return null
    }

    /** What the rules ask of its tags: tidy tags, single spaces, within the limits. */
    private val tagsWellFormed: Boolean get() {
        if (tags.isEmpty()) return true
        val list = tags.split(' ')
        return list.size <= Tags.MAX_COUNT && list.all { it.length <= Tags.MAX_LENGTH && Tag(it).text == it }
    }
}

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

/** [text] lower case and without accents: what search and type-in answers compare. */
class Folded(text: String) {
    val text: String = decompose(text).replace(COMBINING, "").lowercase()

    private companion object {
        val COMBINING = Regex("\\p{M}+")
    }
}
