package app.rondo.core

import kotlin.js.JsExport

/** A piece of inline text with one look. Hidden clozes become a [BLANK] run when a card is laid out. */
@JsExport
class Run internal constructor(
    val text: String,
    val marks: Int = 0,
    val kind: String = TEXT,
    val reading: String? = null,
    val cloze: Int = 0,
    val hint: String? = null,
) {
    internal fun with(marks: Int = this.marks, kind: String = this.kind, text: String = this.text) =
        Run(text, marks, kind, reading, cloze, hint)

    companion object {
        const val TEXT = "text"
        const val MATH = "math"
        const val RUBY = "ruby"
        const val BREAK = "break"
        const val BLANK = "blank"

        const val BOLD = 1
        const val ITALIC = 2
        const val UNDERLINE = 4
        const val HIGHLIGHT = 8
        const val CODE = 16
        const val SUB = 32
        const val SUP = 64

        /** Set by the layout on the answer of the card's own cloze. */
        const val REVEALED = 128
    }
}

/** A paragraph, a list, a table or display math. */
@JsExport
class TextBlock internal constructor(
    val kind: String,
    val runs: Array<Run> = emptyArray(),
    val items: Array<Array<Run>> = emptyArray(),
    val rows: Array<Array<Array<Run>>> = emptyArray(),
    val header: Boolean = false,
    val tex: String = "",
) {
    companion object {
        const val PARAGRAPH = "p"
        const val BULLETS = "ul"
        const val NUMBERS = "ol"
        const val TABLE = "table"
        const val MATH = "math"
    }
}

/**
 * The text of a field: one string holding paragraphs, lists, pipe tables and `$$` math, with inline
 * `**bold**`, `*italic*`, `__underline__`, `==highlight==`, `` `code` ``, `~sub~`, `^sup^`, `$math$`,
 * `{{c1::cloze::hint}}` and `{base|reading}`. Formatting marks toggle; `\` escapes any character.
 */
object Markup {
    private val ORDERED = Regex("^\\d+\\. ")
    private val SEPARATOR = Regex("^\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")
    private val TOGGLES = listOf(
        "**" to Run.BOLD,
        "__" to Run.UNDERLINE,
        "==" to Run.HIGHLIGHT,
        "*" to Run.ITALIC,
        "~" to Run.SUB,
        "^" to Run.SUP,
    )
    private const val SPECIAL = "\\*_=~^`\${}|"

    fun parse(src: String): List<TextBlock> {
        val lines = src.replace("\r\n", "\n").split('\n')
        val blocks = ArrayList<TextBlock>()
        val paragraph = ArrayList<Run>()
        fun flush() {
            if (paragraph.isNotEmpty()) blocks += TextBlock(TextBlock.PARAGRAPH, paragraph.toTypedArray())
            paragraph.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trim() == "$$" -> {
                    flush()
                    val end = (i + 1 until lines.size).firstOrNull { lines[it].trim() == "$$" } ?: lines.size
                    blocks += TextBlock(TextBlock.MATH, tex = lines.subList(i + 1, end).joinToString("\n").trim())
                    i = end + 1
                    continue
                }

                line.startsWith("- ") || ORDERED.containsMatchIn(line) -> {
                    flush()
                    val ordered = !line.startsWith("- ")
                    val items = ArrayList<Array<Run>>()
                    while (i < lines.size &&
                        (if (ordered) ORDERED.containsMatchIn(lines[i]) else lines[i].startsWith("- "))
                    ) {
                        val text = if (ordered) lines[i].replaceFirst(ORDERED, "") else lines[i].substring(2)
                        items += inline(text).toTypedArray()
                        i++
                    }
                    val kind = if (ordered) TextBlock.NUMBERS else TextBlock.BULLETS
                    blocks += TextBlock(kind, items = items.toTypedArray())
                    continue
                }

                line.startsWith("|") -> {
                    flush()
                    val rows = ArrayList<Array<Array<Run>>>()
                    var header = false
                    while (i < lines.size && lines[i].startsWith("|")) {
                        if (rows.size == 1 && SEPARATOR.matches(lines[i])) header = true else rows += cells(lines[i])
                        i++
                    }
                    blocks += TextBlock(TextBlock.TABLE, rows = rows.toTypedArray(), header = header)
                    continue
                }

                line.isBlank() -> flush()

                else -> {
                    if (paragraph.isNotEmpty()) paragraph += Run("", kind = Run.BREAK)
                    paragraph += inline(line)
                }
            }
            i++
        }
        flush()
        return blocks
    }

    private fun cells(line: String): Array<Array<Run>> {
        val parts = ArrayList<String>()
        val cell = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && i + 1 < line.length) {
                cell.append(c).append(line[++i])
            } else if (c == '|') {
                parts += cell.toString()
                cell.clear()
            } else {
                cell.append(c)
            }
            i++
        }
        parts += cell.toString()
        if (parts.first().isBlank()) parts.removeAt(0)
        if (parts.isNotEmpty() && parts.last().isBlank()) parts.removeAt(parts.lastIndex)
        return parts.map { inline(it.trim()).toTypedArray() }.toTypedArray()
    }

    /** Inline content only (one line). */
    fun inline(src: String, cloze: Int = 0, hint: String? = null): List<Run> {
        val runs = ArrayList<Run>()
        val text = StringBuilder()
        var marks = 0
        fun flush() {
            if (text.isNotEmpty()) runs += Run(text.toString(), marks, cloze = cloze, hint = hint)
            text.clear()
        }
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '\\' && i + 1 < src.length) {
                text.append(src[i + 1])
                i += 2
                continue
            }
            val toggle = TOGGLES.firstOrNull { src.startsWith(it.first, i) }
            if (toggle != null) {
                flush()
                marks = marks xor toggle.second
                i += toggle.first.length
                continue
            }
            when (c) {
                '`' -> {
                    val end = src.indexOf('`', i + 1)
                    if (end > 0) {
                        flush()
                        runs += Run(src.substring(i + 1, end), marks or Run.CODE, cloze = cloze, hint = hint)
                        i = end + 1
                        continue
                    }
                }

                '$' -> {
                    val end = mathEnd(src, i)
                    if (end > 0) {
                        flush()
                        val tex = src.substring(i + 1, end).replace("\\$", "$")
                        runs += Run(tex, marks, Run.MATH, cloze = cloze, hint = hint)
                        i = end + 1
                        continue
                    }
                }

                '{' -> {
                    val cl = if (cloze == 0) Regex("^\\{\\{c(\\d+)::").find(src.substring(i)) else null
                    val close = if (cl != null) closingBraces(src, i + cl.value.length) else -1
                    if (cl != null && close > 0) {
                        flush()
                        val body = src.substring(i + cl.value.length, close)
                        val split = lastSeparator(body)
                        val inner = if (split >= 0) body.substring(0, split) else body
                        val hintText = if (split >= 0) body.substring(split + 2) else null
                        val number = cl.groupValues[1].toInt()
                        runs += inline(inner, number, hintText).map { it.with(marks = it.marks or marks) }
                        i = close + 2
                        continue
                    }
                    val end = src.indexOf('}', i)
                    val bar = src.indexOf('|', i)
                    if (end > 0 && bar in i + 2 until end) {
                        flush()
                        val base = unescape(src.substring(i + 1, bar))
                        val reading = unescape(src.substring(bar + 1, end))
                        runs += Run(base, marks, Run.RUBY, reading, cloze, hint)
                        i = end + 1
                        continue
                    }
                }
            }
            text.append(c)
            i++
        }
        flush()
        return runs
    }

    /** Pandoc's rule: `$` opens before a non-space; it closes after a non-space, not before a digit. */
    private fun mathEnd(src: String, start: Int): Int {
        if (start + 1 >= src.length || src[start + 1].isWhitespace() || src[start + 1] == '$') return -1
        var j = start + 1
        while (j < src.length) {
            if (src[j] == '\\') {
                j += 2
                continue
            }
            if (src[j] == '$' && !src[j - 1].isWhitespace() && !(j + 1 < src.length && src[j + 1].isDigit())) return j
            j++
        }
        return -1
    }

    private fun closingBraces(src: String, from: Int): Int {
        var j = from
        while (j < src.length - 1) {
            if (src[j] == '\\') {
                j++
            } else if (src[j] == '}' && src[j + 1] == '}') {
                return j
            }
            j++
        }
        return -1
    }

    private fun lastSeparator(body: String): Int {
        var found = -1
        var j = 0
        while (j < body.length - 1) {
            if (body[j] == '\\') {
                j++
            } else if (body[j] == ':' && body[j + 1] == ':') {
                found = j
            }
            j++
        }
        return found
    }

    private fun unescape(s: String): String = Regex("\\\\(.)").replace(s) { it.groupValues[1] }

    // Writing ------------------------------------------------------------------------------------

    fun write(blocks: List<TextBlock>): String = blocks.joinToString("\n\n") { block ->
        when (block.kind) {
            TextBlock.MATH -> "$$\n${block.tex}\n$$"

            TextBlock.BULLETS -> block.items.joinToString("\n") { "- " + writeRuns(it.toList()) }

            TextBlock.NUMBERS -> block.items.withIndex().joinToString("\n") { (n, item) ->
                "${n + 1}. ${writeRuns(item.toList())}"
            }

            TextBlock.TABLE -> {
                val rows = block.rows.map { row ->
                    "| " + row.joinToString(" | ") { writeRuns(it.toList()).replace('\n', ' ') } + " |"
                }
                if (block.header && rows.isNotEmpty()) {
                    val columns = block.rows[0].size
                    (listOf(rows[0], "|" + "---|".repeat(columns)) + rows.drop(1)).joinToString("\n")
                } else {
                    rows.joinToString("\n")
                }
            }

            else -> writeRuns(block.runs.toList()).split('\n').joinToString("\n") { line ->
                // A line that would start a list, a table or math stays a line of text.
                val list = line.startsWith("- ") || ORDERED.containsMatchIn(line)
                if (list || line.startsWith("|") || line.trim() == "$$") "\\$line" else line
            }
        }
    }

    /** Inline runs back to markup. */
    fun writeRuns(runs: List<Run>): String {
        val out = StringBuilder()
        var marks = 0
        fun mark(target: Int) {
            for ((delimiter, bit) in TOGGLES) if ((marks xor target) and bit != 0) out.append(delimiter)
            marks = target
        }
        var i = 0
        while (i < runs.size) {
            val run = runs[i]
            if (run.cloze > 0) {
                mark(0)
                var end = i
                while (end < runs.size && runs[end].cloze == run.cloze) end++
                val inner = runs.subList(i, end).map { Run(it.text, it.marks, it.kind, it.reading) }
                out.append("{{c").append(run.cloze).append("::").append(writeRuns(inner))
                run.hint?.let { out.append("::").append(escape(it)) }
                out.append("}}")
                i = end
                continue
            }
            val style = run.marks and (Run.CODE or Run.REVEALED).inv()
            when (run.kind) {
                Run.BREAK -> {
                    mark(0)
                    out.append('\n')
                }

                Run.MATH -> {
                    mark(style)
                    out.append('$').append(run.text.trim().replace("$", "\\$")).append('$')
                }

                Run.RUBY -> {
                    mark(style)
                    out.append("{${escape(run.text)}|${escape(run.reading.orEmpty())}}")
                }

                Run.TEXT -> {
                    mark(style)
                    // `$x$5` would not close the math.
                    if (out.endsWith('$') && run.text.firstOrNull()?.isDigit() == true) out.append('\\')
                    if (run.marks and Run.CODE != 0) {
                        out.append('`').append(run.text.replace('`', '\'')).append('`')
                    } else {
                        out.append(escape(run.text))
                    }
                }
            }
            i++
        }
        mark(0)
        return out.toString()
    }

    private fun escape(s: String): String = buildString {
        s.forEach {
            if (it in SPECIAL) append('\\')
            append(it)
        }
    }

    /** A run, for code that makes text from other formats. */
    fun run(
        text: String,
        marks: Int = 0,
        kind: String = Run.TEXT,
        reading: String? = null,
        cloze: Int = 0,
        hint: String? = null,
    ) = Run(text, marks, kind, reading, cloze, hint)

    fun block(
        kind: String,
        runs: List<Run> = emptyList(),
        items: List<List<Run>> = emptyList(),
        rows: List<List<List<Run>>> = emptyList(),
        header: Boolean = false,
        tex: String = "",
    ) = TextBlock(
        kind,
        runs.toTypedArray(),
        items.map { it.toTypedArray() }.toTypedArray(),
        rows.map { r -> r.map { it.toTypedArray() }.toTypedArray() }.toTypedArray(),
        header,
        tex,
    )

    // Reading ------------------------------------------------------------------------------------

    /** Text without formatting: what search, speech and type-in compare against. */
    fun plain(src: String): String = parse(src).joinToString("\n") { block ->
        when (block.kind) {
            TextBlock.MATH -> block.tex
            TextBlock.BULLETS, TextBlock.NUMBERS -> block.items.joinToString("\n") { plain(it) }
            TextBlock.TABLE -> block.rows.joinToString("\n") { row -> row.joinToString(" ") { plain(it) } }
            else -> plain(block.runs)
        }
    }

    fun plain(runs: Array<Run>): String = runs.joinToString("") { if (it.kind == Run.BREAK) "\n" else it.text }

    /** The cloze numbers used in [src]. */
    fun clozes(src: String): Set<Int> = parse(src).flatMap { block ->
        val cells = block.rows.flatMap { row -> row.flatMap { it.asList() } }
        block.runs.asList() + block.items.flatMap { it.asList() } + cells
    }.map { it.cloze }.filter { it > 0 }.sorted().toSet()

    /** Applies [f] to every run, keeping the block structure. */
    fun mapRuns(blocks: List<TextBlock>, f: (List<Run>) -> List<Run>): List<TextBlock> = blocks.map { b ->
        TextBlock(
            b.kind,
            f(b.runs.asList()).toTypedArray(),
            b.items.map { f(it.asList()).toTypedArray() }.toTypedArray(),
            b.rows.map { row -> row.map { f(it.asList()).toTypedArray() }.toTypedArray() }.toTypedArray(),
            b.header,
            b.tex,
        )
    }
}
