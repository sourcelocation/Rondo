package app.rondo.core.files.application

import app.rondo.core.kernel.domain.DatedIds
import app.rondo.core.notes.domain.FieldDef
import app.rondo.core.notes.domain.Note
import app.rondo.core.notes.domain.RondoTemplate
import app.rondo.core.notes.domain.Tags
import app.rondo.core.notes.domain.Template
import kotlin.time.Clock

/** A row left out of an import, counting from 1 among the rows that become notes. */
class Skipped(val row: Int, val why: String) {
    companion object {
        /** Its first field is already in the deck, or earlier in the file. */
        const val DUPLICATE = "duplicate"

        /** It makes no cards: the fields its template asks for are empty. */
        const val EMPTY = "empty"

        /** A field can't hold what it was given: an image or a sound field takes a media file's hash. */
        const val INVALID = "invalid"
    }
}

class Made(val notes: List<Note>, val skipped: List<Skipped>)

/**
 * Cells to make notes from, a row a note, as a spreadsheet holds them: [cells] every row, each as
 * wide as the widest. Whether the first row names the columns is guessed unless [header] says: it
 * does when one of its cells is a field name of [templates], or a usual column title. Which note
 * type the rows fill, where each column goes and the notes they make are decided here, whatever
 * file they came from. Cells are markup, the same as a note's fields; a file of another format is
 * turned into markup by the caller ([notes]'s `cell`).
 */
class Rows(
    val cells: List<List<String>>,
    templates: Collection<Template> = Template.builtins.values,
    header: Boolean? = null,
) {
    val header: Boolean = header ?: run {
        val names = templates.flatMap { t -> t.fields.map { it.name.lowercase() } }.toSet() + FIRST + SECOND +
            setOf(TAGS, "tag")
        cells.firstOrNull().orEmpty().any { it.trim().lowercase() in names }
    }

    val width: Int get() = cells.maxOfOrNull { it.size } ?: 0

    /** The rows that become notes. */
    val body: List<List<String>> get() = if (header) cells.drop(1) else cells

    /** The note type it most likely fills: one whose fields its columns name, Cloze when it has clozes, or Basic. */
    fun template(templates: List<Template>): Template {
        val usable = templates.filter(::fits)
        val titles = if (header) cells.first().map { it.trim().lowercase() } else emptyList()
        val named = usable.map { t -> t to t.fields.count { it.name.lowercase() in titles } }
            .filter { it.second > 0 }
            .maxWithOrNull(compareBy({ it.second }, { -it.first.fields.size }))
        if (named != null) return named.first
        val cloze = body.any { row -> row.any { CLOZE.containsMatchIn(it) } }
        val fallback = if (cloze) Template.CLOZE else Template.BASIC
        return usable.firstOrNull { it.id == fallback.id } ?: fallback
    }

    /** Whether its rows can fill [t]: one of Rondo's own, with text fields. */
    fun fits(t: Template): Boolean =
        t is RondoTemplate && t.deletedAt == null && t.fields.any { it.kind != FieldDef.Kind.OCCLUSION }

    /**
     * Where each of its columns goes in [t]: a field id, [TAGS], or null to leave it out. Named
     * columns go to the field of their name; otherwise columns fill the text fields in order.
     */
    fun columns(t: Template): List<String?> {
        val fields = t.fields.filter { it.kind != FieldDef.Kind.OCCLUSION }
        if (header) {
            val taken = HashSet<String>()
            val named = cells.first().map { title ->
                val name = title.trim().lowercase()
                val field = fields.firstOrNull { it.name.lowercase() == name }
                    ?: fields.getOrNull(0).takeIf { name in FIRST }
                    ?: fields.getOrNull(1).takeIf { name in SECOND }
                when {
                    field != null && taken.add(field.id.toString()) -> field.id.toString()
                    name == TAGS || name == "tag" -> TAGS.takeIf { taken.add(TAGS) }
                    else -> null
                }
            }
            if (named.any { it != null && it != TAGS }) return named
        }
        val text = fields.filter { it.kind == FieldDef.Kind.TEXT }
        return List(width) { i -> text.getOrNull(i)?.id?.toString() }
    }

    /**
     * Notes of [t] in [deck] from [rows], each cell into the field its column goes to ([columns]),
     * read by [cell]. Several columns into one field join as paragraphs. A row whose first field is
     * in [existing] (by [Template.key]), or earlier in [rows], is skipped, and so is one that makes
     * no card. Ids keep the rows' order, ending at [now].
     */
    fun notes(
        rows: List<List<String>>,
        columns: List<String?>,
        t: Template,
        deck: String,
        owner: String?,
        clock: () -> Long,
        existing: Set<String> = emptySet(),
        tags: Tags = Tags.NONE,
        now: Long = Clock.System.now().toEpochMilliseconds(),
        cell: (String) -> String = { it },
    ): Made {
        val seen = existing.toHashSet()
        val ids = DatedIds(now - rows.size)
        val notes = ArrayList<Note>()
        val skipped = ArrayList<Skipped>()
        for ((i, row) in rows.withIndex()) {
            val values = LinkedHashMap<String, String>()
            var own = Tags.NONE
            for ((c, raw) in row.withIndex()) {
                val to = columns.getOrNull(c) ?: continue
                val v = raw.trim()
                if (v.isEmpty()) continue
                if (to == TAGS) {
                    own += v.split(TAG_SEPARATORS)
                } else {
                    val markup = cell(v).trim()
                    if (markup.isNotEmpty()) values[to] = values[to]?.let { "$it\n\n$markup" } ?: markup
                }
            }
            val note = Note(ids.next(), deck, t.id, values, clock(), owner, tags = (tags + own.list).text)
            val key = t.key(note.fields)
            when {
                t.problem(note.fields) != null -> skipped += Skipped(i + 1, Skipped.INVALID)
                t.cards(note).isEmpty() -> skipped += Skipped(i + 1, Skipped.EMPTY)
                key != null && !seen.add(key) -> skipped += Skipped(i + 1, Skipped.DUPLICATE)
                else -> notes += note
            }
        }
        return Made(notes, skipped)
    }

    companion object {
        /** A column holding each note's tags, rather than a field. */
        const val TAGS = "tags"

        private val CLOZE = Regex("\\{\\{c\\d+::")
        private val TAG_SEPARATORS = Regex("[,;\\s]+")

        /** Column titles that mean a note's first and second field, whatever the template calls them. */
        private val FIRST = setOf("front", "question", "term", "word", "prompt")
        private val SECOND = setOf("back", "answer", "definition", "meaning", "translation")
    }
}
