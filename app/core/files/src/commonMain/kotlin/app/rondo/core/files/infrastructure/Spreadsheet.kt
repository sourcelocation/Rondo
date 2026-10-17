package app.rondo.core.files.infrastructure

import app.rondo.core.files.application.Rows
import app.rondo.core.notes.domain.Note
import app.rondo.core.notes.domain.Template
import com.jsoizo.kotlincsv.CsvDialect
import com.jsoizo.kotlincsv.csvReader
import com.jsoizo.kotlincsv.csvWriter
import com.jsoizo.kotlincsv.reader.ExcessFieldsRowBehaviour
import com.jsoizo.kotlincsv.reader.InsufficientFieldsRowBehaviour

/**
 * A CSV or TSV file of notes, a row each: read from [text], guessing what separates its cells unless
 * [separator] says. Its cells are [rows], which say whether the first row names the columns (unless
 * [header] does, guessed with [templates]) and what notes they make. [html]: its cells are HTML, as
 * Anki writes them; [anki]: it began with the `#` lines of Anki's text export. What Rondo writes
 * comes back unchanged.
 */
class Spreadsheet(
    val text: String,
    templates: Collection<Template> = Template.builtins.values,
    separator: Char? = null,
    header: Boolean? = null,
) {
    /** [notes] of [template] as a CSV file: a row naming the fields and Tags, then a row a note. */
    constructor(template: Template, notes: List<Note>) : this(write(template, notes), listOf(template), ',', true)

    val separator: Char
    val rows: Rows
    val html: Boolean
    val anki: Boolean

    init {
        val lines = text.removePrefix("\uFEFF").lineSequence()
        // Anki's text export starts with lines like "#separator:tab": not cards.
        val directives = lines.takeWhile { it.startsWith("#") }.count()
        val content = if (directives == 0) text.removePrefix("\uFEFF") else lines.drop(directives).joinToString("\n")
        this.separator = separator ?: SEPARATORS.maxBy { score(content, it) }
        val cells = parse(content, this.separator)
        rows = Rows(cells, templates, header)
        html = cells.any { row -> row.any { HTML.containsMatchIn(it) } }
        anki = directives > 0
    }

    companion object {
        /** What separates cells, in the order a tie is settled. */
        val SEPARATORS = listOf(',', '\t', ';', '|')

        /** The widest file read: kotlin-csv cuts every row to the first's width, so the first is this wide. */
        private const val WIDEST = 64
        private val HTML = Regex("<(br|div|p|b|i|u|span|img|ul|ol|li|sub|sup|font|table|strong|em)\\b[^>]*>|&nbsp;")

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
