package app.rondo.client.anki

import app.rondo.core.markup.domain.Markup
import app.rondo.core.markup.domain.Run
import app.rondo.core.markup.domain.TextBlock
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode

/**
 * One of Anki's fields, its [html] read as Rondo's [markup]: formatting, lines, lists, tables, ruby,
 * clozes and MathJax, with [furigana] (`漢字[かんじ]`) as ruby where the note type reads it so. The
 * pictures and sounds it shows are [images] and [sounds], by hash (from `rondo-media:` links).
 */
class AnkiHtml(html: String, private val furigana: Boolean = false) {
    private val blocks = ArrayList<TextBlock>()
    private var runs = ArrayList<Run>()
    private var marks = 0
    private var cloze = 0
    private var clozeStart = 0
    private var hint: StringBuilder? = null
    private var math: StringBuilder? = null
    private var mathEnd = ""
    private var nested = 0
    val images = ArrayList<String>()
    val sounds = ArrayList<String>()
    val markup: String

    init {
        Ksoup.parseBodyFragment(html).body().childNodes().forEach(::node)
        math?.let { add(it.toString()) }
        paragraph()
        markup = Markup(blocks).text
    }

    private fun node(n: Node) {
        if (n is TextNode) return text(n.text().replace(' ', ' '))
        if (n !is Element) return
        when (val tag = n.normalName()) {
            "br" -> newline(force = true)

            "img" -> media(n.attr("src"))?.let(images::add)

            "script", "style", "head", "title" -> Unit

            "ul", "ol", "table" -> if (nested > 0 || math != null) {
                block(n)
            } else {
                paragraph()
                nested++
                blocks += if (tag == "table") {
                    val cell = { e: Element -> e.normalName() == "td" || e.normalName() == "th" }
                    val rows = n.select("tr").map { tr -> tr.children().filter(cell).map(::inline) }
                    val header = n.selectFirst("tr")?.children()?.any { it.normalName() == "th" } == true
                    TextBlock(TextBlock.TABLE, emptyList(), rows = rows.filter { it.isNotEmpty() }, header = header)
                } else {
                    val kind = if (tag == "ul") TextBlock.BULLETS else TextBlock.NUMBERS
                    TextBlock(kind, emptyList(), items = n.children().filter { it.normalName() == "li" }.map(::inline))
                }
                nested--
            }

            "ruby" -> {
                val reading = n.select("rt").text()
                n.select("rt, rp").remove()
                if (math != null || hint != null) plain(n.text()) else add(n.text(), Run.RUBY, reading)
            }

            "div", "p", "li", "tr", "blockquote", "pre", "h1", "h2", "h3", "h4", "h5", "h6" -> block(n)

            else -> {
                val before = marks
                marks = marks or (MARKS[tag] ?: 0) or style(n.attr("style"))
                n.childNodes().forEach(::node)
                marks = before
            }
        }
    }

    private fun block(e: Element) {
        newline()
        e.childNodes().forEach(::node)
        newline()
    }

    /** A list item's or table cell's content, on one line. */
    private fun inline(e: Element): List<Run> {
        val outer = runs
        runs = ArrayList()
        e.childNodes().forEach(::node)
        trimEnd()
        return runs.map { if (it.kind == Run.BREAK) Run(" ") else it }.also { runs = outer }
    }

    private fun text(s: String) {
        var last = 0
        for (m in TOKENS.findAll(s)) {
            plain(s.substring(last, m.range.first))
            token(m)
            last = m.range.last + 1
        }
        plain(s.substring(last))
    }

    private fun token(m: MatchResult) {
        val t = m.value
        val opens = MATH[t]
        when {
            math != null -> if (t == mathEnd) endMath() else math!!.append(t)

            hint != null && t != "}}" -> hint!!.append(t)

            opens != null -> {
                math = StringBuilder()
                mathEnd = opens
            }

            t.startsWith("[sound:") -> media(m.groupValues[2])?.let(sounds::add)

            t.startsWith("{{c") && cloze == 0 -> {
                cloze = m.groupValues[1].toInt()
                clozeStart = runs.size
            }

            t == "::" && cloze > 0 -> hint = StringBuilder()

            t == "}}" && cloze > 0 -> {
                val h = hint?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                val first = (clozeStart until runs.size).firstOrNull { runs[it].cloze == cloze }
                if (h != null && first != null) {
                    runs[first] = runs[first].let { Run(it.text, it.marks, it.kind, it.reading, it.cloze, h) }
                }
                cloze = 0
                hint = null
            }

            else -> plain(t)
        }
    }

    private fun endMath() {
        val tex = math.toString().trim()
        val display = mathEnd != "\\)" && mathEnd != "[/$]"
        math = null
        if (tex.isEmpty()) return
        if (display && nested == 0 && cloze == 0) {
            paragraph()
            blocks += TextBlock(TextBlock.MATH, emptyList(), tex = tex)
        } else {
            runs += Run(tex, marks, Run.MATH, cloze = cloze)
        }
    }

    private fun plain(s: String) {
        if (s.isEmpty()) return
        (math ?: hint)?.let {
            it.append(s)
            return
        }
        if (!furigana) return add(s)
        var last = 0
        for (m in FURIGANA.findAll(s)) {
            add(s.substring(last, m.range.first))
            add(m.groupValues[1], Run.RUBY, m.groupValues[2])
            last = m.range.last + 1
        }
        add(s.substring(last))
    }

    /** Adds text as HTML shows it: spaces collapsed, none at the start of a line. */
    private fun add(s: String, kind: String = Run.TEXT, reading: String? = null) {
        val collapsed = s.replace(SPACES, " ")
        val lineStart = runs.lastOrNull().let { it == null || it.kind == Run.BREAK || it.text.endsWith(' ') }
        val text = if (kind == Run.TEXT && lineStart) collapsed.trimStart() else collapsed
        if (text.isNotEmpty()) runs += Run(text, marks, kind, reading, cloze)
    }

    private fun newline(force: Boolean = false) {
        (math ?: hint)?.let {
            it.append(' ')
            return
        }
        trimEnd()
        if (runs.isNotEmpty() && (force || runs.last().kind != Run.BREAK)) runs += Run("", kind = Run.BREAK)
    }

    private fun trimEnd() {
        val last = runs.lastOrNull()?.takeIf { it.kind == Run.TEXT && it.text.last().isWhitespace() } ?: return
        val text = last.text.trimEnd()
        if (text.isEmpty()) {
            runs.removeAt(runs.lastIndex)
        } else {
            runs[runs.lastIndex] = Run(text, last.marks, last.kind, last.reading, last.cloze, last.hint)
        }
    }

    private fun paragraph() {
        trimEnd()
        while (runs.lastOrNull()?.kind == Run.BREAK) runs.removeAt(runs.lastIndex)
        if (runs.isNotEmpty()) blocks += TextBlock(TextBlock.PARAGRAPH, runs.toList())
        runs = ArrayList()
    }

    private companion object {
        val TOKENS = Regex("""\{\{c(\d+)::|::|\}\}|\\[()\[\]]|\[/?\$\$?\]|\[/?latex\]|\[sound:([^\]]+)\]""")
        val MATH = mapOf(
            "\\(" to "\\)",
            "\\[" to "\\]",
            "[$]" to "[/$]",
            "[$$]" to "[/$$]",
            "[latex]" to "[/latex]",
        )
        val FURIGANA = Regex(""" ?([^ \[\]]+)\[([^\]]+)\]""")
        val MEDIA = Regex("rondo-media:([0-9a-f]{64})")
        val SPACES = Regex("\\s{2,}")
        val BOLD = Regex("font-weight:\\s*(bold|[6-9]00)")
        val HIGHLIGHT =
            Regex("background(-color)?:\\s*(?!transparent|inherit|initial|none|white|#fff|rgba?\\(255,\\s*255,\\s*255)")
        val MARKS = mapOf(
            "b" to Run.BOLD, "strong" to Run.BOLD, "i" to Run.ITALIC, "em" to Run.ITALIC,
            "u" to Run.UNDERLINE, "ins" to Run.UNDERLINE, "mark" to Run.HIGHLIGHT,
            "code" to Run.CODE, "kbd" to Run.CODE, "sub" to Run.SUB, "sup" to Run.SUP,
        )

        fun media(src: String): String? = MEDIA.find(src)?.groupValues?.get(1)

        fun style(css: String): Int {
            if (css.isEmpty()) return 0
            val s = css.lowercase()
            var marks = 0
            if (BOLD.containsMatchIn(s)) marks = marks or Run.BOLD
            if ("italic" in s) marks = marks or Run.ITALIC
            if ("underline" in s) marks = marks or Run.UNDERLINE
            if (HIGHLIGHT.containsMatchIn(s)) marks = marks or Run.HIGHLIGHT
            return marks
        }
    }
}
