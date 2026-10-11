package app.rondo.core

import kotlin.js.JsExport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A piece of inline text with one look. Hidden clozes become a [BLANK] run when a card is laid out. */
@JsExport
class Run(
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
class TextBlock(
    val kind: String,
    val runs: Array<Run> = emptyArray(),
    val items: Array<Array<Run>> = emptyArray(),
    val rows: Array<Array<Array<Run>>> = emptyArray(),
    val header: Boolean = false,
    val tex: String = "",
) {
    /** The same block from lists: what readers of other formats build. */
    @JsExport.Ignore
    constructor(
        kind: String,
        runs: List<Run>,
        items: List<List<Run>> = emptyList(),
        rows: List<List<List<Run>>> = emptyList(),
        header: Boolean = false,
        tex: String = "",
    ) : this(
        kind,
        runs.toTypedArray(),
        items.map { it.toTypedArray() }.toTypedArray(),
        rows.map { r -> r.map { it.toTypedArray() }.toTypedArray() }.toTypedArray(),
        header,
        tex,
    )

    /** This block with [f] applied to every run of it, keeping its structure. */
    @JsExport.Ignore
    fun map(f: (List<Run>) -> List<Run>): TextBlock = TextBlock(
        kind,
        f(runs.asList()).toTypedArray(),
        items.map { f(it.asList()).toTypedArray() }.toTypedArray(),
        rows.map { row -> row.map { f(it.asList()).toTypedArray() }.toTypedArray() }.toTypedArray(),
        header,
        tex,
    )

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
@JsExport
class Markup(val text: String) {
    /** Markup written from [blocks]. */
    @JsExport.Ignore
    constructor(blocks: List<TextBlock>) : this(write(blocks))

    @JsExport.Ignore
    val blocks: List<TextBlock> get() = parse(text)

    /** Its text without formatting: lines, list items and cells each on a line. */
    val plain: String get() = plain(text)

    /**
     * As the HTML Anki keeps in a field: paragraphs a blank line apart, lists, tables, ruby,
     * MathJax's `\(…\)` and `\[…\]`, and clozes as `{{c1::…::hint}}`.
     */
    val html: String get() = html(text)

    /** The cloze numbers it uses. */
    @JsExport.Ignore
    val clozes: Set<Int> get() = clozes(text)

    /**
     * As a ProseMirror document (what TipTap edits), so editors only wire commands. Node and mark
     * names are TipTap's: paragraph, hardBreak, bulletList, orderedList, listItem, table, tableRow,
     * tableHeader, tableCell, inlineMath, blockMath; marks bold, italic, underline, highlight, code,
     * subscript, superscript, plus Rondo's cloze mark and ruby node.
     */
    val document: String get() = document(text)

    private companion object {
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

        private fun parse(src: String): List<TextBlock> {
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
                            if (rows.size == 1 && SEPARATOR.matches(lines[i])) {
                                header = true
                            } else {
                                rows += cells(lines[i])
                            }
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
        private fun inline(src: String, cloze: Int = 0, hint: String? = null): List<Run> {
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
                val digitAfter = j + 1 < src.length && src[j + 1].isDigit()
                if (src[j] == '$' && !src[j - 1].isWhitespace() && !digitAfter) return j
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

        private fun write(blocks: List<TextBlock>): String = blocks.joinToString("\n\n") { block ->
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
        private fun writeRuns(runs: List<Run>): String {
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

        // HTML -----------------------------------------------------------------------------------------

        /**
         * [src] as the HTML Anki keeps in a field: paragraphs a blank line apart, lists, tables, ruby,
         * MathJax's `\(…\)` and `\[…\]`, and clozes as `{{c1::…::hint}}`.
         */
        private fun html(src: String): String = parse(src).joinToString("<br><br>") { block ->
            when (block.kind) {
                TextBlock.MATH -> "\\[" + escapeHtml(block.tex) + "\\]"

                TextBlock.BULLETS, TextBlock.NUMBERS -> {
                    val tag = if (block.kind == TextBlock.BULLETS) "ul" else "ol"
                    block.items.joinToString("", "<$tag>", "</$tag>") { "<li>${htmlRuns(it.asList())}</li>" }
                }

                TextBlock.TABLE -> block.rows.withIndex().joinToString("", "<table>", "</table>") { (r, row) ->
                    val cell = if (r == 0 && block.header) "th" else "td"
                    row.joinToString("", "<tr>", "</tr>") { "<$cell>${htmlRuns(it.asList())}</$cell>" }
                }

                else -> htmlRuns(block.runs.asList())
            }
        }

        private val HTML_MARKS = listOf(
            Run.BOLD to "b",
            Run.ITALIC to "i",
            Run.UNDERLINE to "u",
            Run.HIGHLIGHT to "mark",
            Run.CODE to "code",
            Run.SUB to "sub",
            Run.SUP to "sup",
        )

        private fun htmlRuns(runs: List<Run>): String {
            val out = StringBuilder()
            var i = 0
            while (i < runs.size) {
                val run = runs[i]
                if (run.cloze > 0) {
                    var end = i
                    while (end < runs.size && runs[end].cloze == run.cloze) end++
                    val inner = runs.subList(i, end).map { Run(it.text, it.marks, it.kind, it.reading) }
                    out.append("{{c").append(run.cloze).append("::").append(htmlRuns(inner))
                    run.hint?.let { out.append("::").append(escapeHtml(it)) }
                    out.append("}}")
                    i = end
                    continue
                }
                val content = when (run.kind) {
                    Run.BREAK -> "<br>"
                    Run.MATH -> "\\(" + escapeHtml(run.text) + "\\)"
                    Run.RUBY -> "<ruby>${escapeHtml(run.text)}<rt>${escapeHtml(run.reading.orEmpty())}</rt></ruby>"
                    else -> escapeHtml(run.text)
                }
                val tags = HTML_MARKS.filter { run.marks and it.first != 0 }.map { it.second }
                tags.forEach { out.append('<').append(it).append('>') }
                out.append(content)
                tags.asReversed().forEach { out.append("</").append(it).append('>') }
                i++
            }
            return out.toString()
        }

        private fun escapeHtml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        // Reading ------------------------------------------------------------------------------------

        /** Text without formatting: what search, speech and type-in compare against. */
        private fun plain(src: String): String = parse(src).joinToString("\n") { block ->
            when (block.kind) {
                TextBlock.MATH -> block.tex
                TextBlock.BULLETS, TextBlock.NUMBERS -> block.items.joinToString("\n") { plain(it) }
                TextBlock.TABLE -> block.rows.joinToString("\n") { row -> row.joinToString(" ") { plain(it) } }
                else -> plain(block.runs)
            }
        }

        private fun plain(runs: Array<Run>): String = runs.joinToString("") {
            if (it.kind ==
                Run.BREAK
            ) {
                "\n"
            } else {
                it.text
            }
        }

        /** The cloze numbers used in [src]. */
        private fun clozes(src: String): Set<Int> = parse(src).flatMap { block ->
            val cells = block.rows.flatMap { row -> row.flatMap { it.asList() } }
            block.runs.asList() + block.items.flatMap { it.asList() } + cells
        }.map { it.cloze }.filter { it > 0 }.sorted().toSet()

        private fun document(markup: String): String = buildJsonObject {
            put("type", "doc")
            put("content", JsonArray(Markup.parse(markup).map(::block).ifEmpty { listOf(paragraph(emptyArray())) }))
        }.toString()

        private fun block(b: TextBlock): JsonObject = when (b.kind) {
            TextBlock.MATH -> node("blockMath", attrs = buildJsonObject { put("latex", b.tex) })

            TextBlock.BULLETS, TextBlock.NUMBERS -> node(
                if (b.kind == TextBlock.BULLETS) "bulletList" else "orderedList",
                b.items.map { node("listItem", listOf(paragraph(it))) },
            )

            TextBlock.TABLE -> node(
                "table",
                b.rows.mapIndexed { r, row ->
                    val cell = if (r == 0 && b.header) "tableHeader" else "tableCell"
                    node("tableRow", row.map { node(cell, listOf(paragraph(it))) })
                },
            )

            else -> paragraph(b.runs)
        }

        private fun paragraph(runs: Array<Run>) = node("paragraph", runs.map(::inline))

        private fun inline(r: Run): JsonObject {
            val marks = buildJsonArray {
                for ((bit, name) in MARKS) if (r.marks and bit != 0) add(node(name))
                if (r.cloze > 0) {
                    val attrs = buildJsonObject {
                        put("n", r.cloze)
                        r.hint?.let { put("hint", it) }
                    }
                    add(node("cloze", attrs = attrs))
                }
            }
            val base = when (r.kind) {
                Run.BREAK -> node("hardBreak")

                Run.MATH -> node("inlineMath", attrs = buildJsonObject { put("latex", r.text) })

                Run.RUBY -> node(
                    "ruby",
                    attrs = buildJsonObject {
                        put("base", r.text)
                        put("reading", r.reading.orEmpty())
                    },
                )

                else -> buildJsonObject {
                    put("type", "text")
                    put("text", r.text)
                }
            }
            return if (marks.isEmpty()) base else JsonObject(base + ("marks" to marks))
        }

        private fun node(type: String, content: List<JsonObject>? = null, attrs: JsonObject? = null) = buildJsonObject {
            put("type", type)
            attrs?.let { put("attrs", it) }
            content?.let { put("content", JsonArray(it)) }
        }
    }
}

/** A ProseMirror document an editor made, read back as markup ([Markup.document]'s other way). */
@JsExport
class EditorDocument(val json: String) {
    val markup: String get() = markup(json)

    private companion object {
        private fun markup(document: String): String {
            val doc = Json.parseToJsonElement(document).jsonObject
            return Markup(doc.children().mapNotNull(::fromBlock)).text
        }

        private fun JsonObject.children(): List<JsonObject> = this["content"]?.jsonArray?.map {
            it.jsonObject
        }.orEmpty()

        private fun JsonObject.type(): String = this["type"]?.jsonPrimitive?.content.orEmpty()

        private fun JsonObject.attr(name: String): String? = this["attrs"]?.jsonObject?.get(name)?.let {
            (it as? JsonPrimitive)?.takeUnless { p -> p.content == "null" }?.content
        }

        private fun fromBlock(n: JsonObject): TextBlock? = when (n.type()) {
            "paragraph" -> TextBlock(TextBlock.PARAGRAPH, runs(n))

            "blockMath" -> TextBlock(TextBlock.MATH, tex = n.attr("latex").orEmpty())

            "bulletList", "orderedList" -> TextBlock(
                if (n.type() == "bulletList") TextBlock.BULLETS else TextBlock.NUMBERS,
                items = n.children().map { item ->
                    item.children().flatMap { runs(it).asList() }.toTypedArray()
                }.toTypedArray(),
            )

            "table" -> {
                val rows = n.children().map { row -> row.children() }
                TextBlock(
                    TextBlock.TABLE,
                    rows = rows.map { row ->
                        row.map { cell -> cell.children().flatMap { runs(it).asList() }.toTypedArray() }.toTypedArray()
                    }.toTypedArray(),
                    header = rows.firstOrNull()?.all { it.type() == "tableHeader" } == true,
                )
            }

            else -> null
        }

        private fun runs(paragraph: JsonObject): Array<Run> = paragraph.children().map { n ->
            val marks = n["marks"]?.jsonArray?.map { it.jsonObject }.orEmpty()
            var bits = 0
            for (m in marks) MARKS.firstOrNull { it.second == m.type() }?.let { bits = bits or it.first }
            val cloze = marks.firstOrNull { it.type() == "cloze" }
            val number = cloze?.get("attrs")?.jsonObject?.get("n")?.jsonPrimitive?.int ?: 0
            val hint = cloze?.attr("hint")
            when (n.type()) {
                "hardBreak" -> Run("", kind = Run.BREAK)
                "inlineMath" -> Run(n.attr("latex").orEmpty(), bits, Run.MATH, cloze = number, hint = hint)
                "ruby" -> Run(n.attr("base").orEmpty(), bits, Run.RUBY, n.attr("reading"), number, hint)
                else -> Run(n["text"]?.jsonPrimitive?.content.orEmpty(), bits, cloze = number, hint = hint)
            }
        }.toTypedArray()
    }
}

/** TipTap's mark names, by Rondo's mark bits. */
private val MARKS = listOf(
    Run.BOLD to "bold",
    Run.ITALIC to "italic",
    Run.UNDERLINE to "underline",
    Run.HIGHLIGHT to "highlight",
    Run.CODE to "code",
    Run.SUB to "subscript",
    Run.SUP to "superscript",
)
