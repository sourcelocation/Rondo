package app.rondo.core.markup.infrastructure

import app.rondo.core.markup.domain.Markup
import app.rondo.core.markup.domain.Run
import app.rondo.core.markup.domain.TextBlock

/**
 * [markup] as the HTML Anki keeps in a field: paragraphs a blank line apart, lists, tables, ruby,
 * MathJax's `\(…\)` and `\[…\]`, and clozes as `{{c1::…::hint}}`.
 */
class FieldHtml(markup: Markup) {
    val html: String = markup.blocks.joinToString("<br><br>") { block ->
        when (block.kind) {
            TextBlock.MATH -> "\\[" + escape(block.tex) + "\\]"

            TextBlock.BULLETS, TextBlock.NUMBERS -> {
                val tag = if (block.kind == TextBlock.BULLETS) "ul" else "ol"
                block.items.joinToString("", "<$tag>", "</$tag>") { "<li>${runs(it.asList())}</li>" }
            }

            TextBlock.TABLE -> block.rows.withIndex().joinToString("", "<table>", "</table>") { (r, row) ->
                val cell = if (r == 0 && block.header) "th" else "td"
                row.joinToString("", "<tr>", "</tr>") { "<$cell>${runs(it.asList())}</$cell>" }
            }

            else -> runs(block.runs.asList())
        }
    }

    private companion object {
        val MARKS = listOf(
            Run.BOLD to "b",
            Run.ITALIC to "i",
            Run.UNDERLINE to "u",
            Run.HIGHLIGHT to "mark",
            Run.CODE to "code",
            Run.SUB to "sub",
            Run.SUP to "sup",
        )

        fun runs(runs: List<Run>): String {
            val out = StringBuilder()
            var i = 0
            while (i < runs.size) {
                val run = runs[i]
                if (run.cloze > 0) {
                    var end = i
                    while (end < runs.size && runs[end].cloze == run.cloze) end++
                    val inner = runs.subList(i, end).map { Run(it.text, it.marks, it.kind, it.reading) }
                    out.append("{{c").append(run.cloze).append("::").append(runs(inner))
                    run.hint?.let { out.append("::").append(escape(it)) }
                    out.append("}}")
                    i = end
                    continue
                }
                val content = when (run.kind) {
                    Run.BREAK -> "<br>"
                    Run.MATH -> "\\(" + escape(run.text) + "\\)"
                    Run.RUBY -> "<ruby>${escape(run.text)}<rt>${escape(run.reading.orEmpty())}</rt></ruby>"
                    else -> escape(run.text)
                }
                val tags = MARKS.filter { run.marks and it.first != 0 }.map { it.second }
                tags.forEach { out.append('<').append(it).append('>') }
                out.append(content)
                tags.asReversed().forEach { out.append("</").append(it).append('>') }
                i++
            }
            return out.toString()
        }

        fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
