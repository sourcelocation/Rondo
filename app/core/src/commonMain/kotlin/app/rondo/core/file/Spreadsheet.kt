package app.rondo.core.file

import app.rondo.core.DatedIds
import app.rondo.core.FieldDef
import app.rondo.core.Note
import app.rondo.core.RondoTemplate
import app.rondo.core.Tags
import app.rondo.core.Template
import com.jsoizo.kotlincsv.CsvDialect
import com.jsoizo.kotlincsv.csvReader
import com.jsoizo.kotlincsv.csvWriter
import com.jsoizo.kotlincsv.reader.ExcessFieldsRowBehaviour
import com.jsoizo.kotlincsv.reader.InsufficientFieldsRowBehaviour
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
 * A CSV or TSV file of notes, a row each: read from [text], guessing what separates its cells unless
 * [separator] says, and whether its first row names the columns unless [header] says (when one of
 * its cells is a field name of [templates], or a usual column title). [html]: its cells are HTML, as
 * Anki writes them; [anki]: it began with the `#` lines of Anki's text export. Cells are markup, the
 * same as a note's fields, so what Rondo writes comes back unchanged; HTML is turned into markup by
 * the caller ([notes]'s `cell`).
 */
class Spreadsheet(
    val text: String,
    templates: Collection<Template> = Template.builtins.values,
    separator: Char? = null,
    header: Boolean? = null,
) {
    /** [notes] of [template] as a CSV file: a row naming the fields and Tags, then a row a note. */
    constructor(template: Template, notes: List<Note>) : this(write(template, notes), listOf(template), ',', true)

    val rows: List<List<String>>
    val separator: Char
    val header: Boolean
    val html: Boolean
    val anki: Boolean

    init {
        val lines = text.removePrefix("\uFEFF").lineSequence()
        // Anki's text export starts with lines like "#separator:tab": not cards.
        val directives = lines.takeWhile { it.startsWith("#") }.count()
        val content = if (directives == 0) text.removePrefix("\uFEFF") else lines.drop(directives).joinToString("\n")
        this.separator = separator ?: SEPARATORS.maxBy { score(content, it) }
        rows = parse(content, this.separator)
        val names = templates.flatMap { t -> t.fields.map { it.name.lowercase() } }.toSet() + FIRST + SECOND +
            setOf(TAGS, "tag")
        this.header = header ?: rows.firstOrNull().orEmpty().any { it.trim().lowercase() in names }
        html = rows.any { row -> row.any { HTML.containsMatchIn(it) } }
        anki = directives > 0
    }

    val width: Int get() = rows.maxOfOrNull { it.size } ?: 0

    /** The rows that become notes. */
    val body: List<List<String>> get() = if (header) rows.drop(1) else rows

    /** The note type it most likely fills: one whose fields its columns name, Cloze when it has clozes, or Basic. */
    fun template(templates: List<Template>): Template {
        val usable = templates.filter(::fits)
        val titles = if (header) rows.first().map { it.trim().lowercase() } else emptyList()
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
            val named = rows.first().map { title ->
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

        /** What separates cells, in the order a tie is settled. */
        val SEPARATORS = listOf(',', '\t', ';', '|')

        /** The widest file read: kotlin-csv cuts every row to the first's width, so the first is this wide. */
        private const val WIDEST = 64
        private val CLOZE = Regex("\\{\\{c\\d+::")
        private val HTML = Regex("<(br|div|p|b|i|u|span|img|ul|ol|li|sub|sup|font|table|strong|em)\\b[^>]*>|&nbsp;")
        private val TAG_SEPARATORS = Regex("[,;\\s]+")

        /** Column titles that mean a note's first and second field, whatever the template calls them. */
        private val FIRST = setOf("front", "question", "term", "word", "prompt")
        private val SECOND = setOf("back", "answer", "definition", "meaning", "translation")

        /**
         * How well [sep] splits the start of [text]: how many rows share a width above one; then the
         * fewer columns (the other separators show up inside cells), then the order of [SEPARATORS].
         */
        private fun score(text: String, sep: Char): Int {
            val widths = runCatching { padded(text, sep).take(20).map { width(it) }.toList() }.getOrNull() ?: return -1
            val (width, count) = widths.filter {
                it > 1
            }.groupingBy { it }.eachCount().maxByOrNull { (w, n) -> n * 100 - w }
                ?: return 0
            return (count * 100 - width.coerceAtMost(99)) * 10 + (SEPARATORS.size - SEPARATORS.indexOf(sep))
        }

        private fun width(row: List<String>) = row.indexOfLast { it.isNotEmpty() } + 1

        /**
         * Rows of [text], every one [WIDEST] wide: kotlin-csv makes each row the first one's width, so a
         * row of empty cells goes first.
         */
        private fun padded(text: String, sep: Char): Sequence<List<String>> {
            val reader = csvReader {
                dialect = CsvDialect(delimiter = sep)
                skipEmptyLine = true
                insufficientFieldsRowBehaviour = InsufficientFieldsRowBehaviour.EMPTY_STRING
                excessFieldsRowBehaviour = ExcessFieldsRowBehaviour.TRIM
            }
            return reader.read((sep.toString().repeat(WIDEST - 1) + "\n" + text).asSequence()).drop(1)
        }

        /** Rows of [text] as wide as the widest, without blank ones. */
        private fun parse(text: String, sep: Char): List<List<String>> {
            val rows = padded(text, sep).filter { r -> r.any { it.isNotBlank() } }.toList()
            val width = rows.maxOfOrNull(::width) ?: 0
            return rows.map { it.take(width) }
        }

        /**
         * [notes] of [t] as a CSV file: a row naming the fields and Tags, then a row a note. It starts
         * with a byte order mark, so spreadsheets read it as UTF-8.
         */
        private fun write(t: Template, notes: List<Note>): String {
            val header = t.fields.map { it.name } + "Tags"
            val rows = notes.map { n -> t.fields.map { n.fields[it.id.toString()].orEmpty() } + n.tags }
            return "\uFEFF" + csvWriter().writeAll(listOf(header) + rows)
        }
    }
}
