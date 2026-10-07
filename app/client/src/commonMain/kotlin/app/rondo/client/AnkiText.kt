package app.rondo.client

import app.rondo.core.Markup
import app.rondo.core.Occlusion
import app.rondo.core.Run
import app.rondo.core.Templates
import app.rondo.core.TextBlock
import app.rondo.core.model.Block
import app.rondo.core.model.CardDef
import app.rondo.core.model.FieldDef
import app.rondo.core.model.Note
import app.rondo.core.model.Rows
import app.rondo.core.model.Template
import app.rondo.core.model.TemplateDef
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode

/**
 * Anki's HTML note types made into Rondo templates: the fields each side shows become blocks, HTML
 * becomes markup, and images and sounds move to fields of their own. Card numbers stay, so progress
 * carries over; the standard note types land on the built-in templates.
 */
object AnkiText {
    private class Ref(val field: Int, val filters: Set<String> = emptySet())

    private val DIVIDER = Ref(-1)
    private val FRONT_SIDE = Ref(0)
    private val TOKEN = Regex("\\{\\{([^}]+)\\}\\}|<hr[^>]*id=[\"']?answer[^>]*>")
    private val SHAPE = Regex("\\{\\{c(\\d+)::image-occlusion:(rect|ellipse|polygon):([^}]*)\\}\\}")
    private val TEXT = FieldDef.Kind.TEXT
    private val IMAGE = FieldDef.Kind.IMAGE
    private val AUDIO = FieldDef.Kind.AUDIO

    /** [rows] with every Anki template, and the notes using it, translated; ones that don't fit stay as they are. */
    fun rows(rows: Rows): Rows {
        val notes = rows.notes.orEmpty().groupBy { it.templateId }
        val templates = ArrayList<Template>()
        val translated = HashMap<String, Note?>()
        for (t in rows.templates.orEmpty()) {
            val result = if (t.kind == Templates.ANKI) translate(t, notes[t.id].orEmpty()) else null
            val (template, out) = result ?: (t to null)
            if (!Templates.isBuiltin(template.id)) templates += template
            if (out != null) notes[t.id].orEmpty().forEach { n -> translated[n.id] = out[n.id] }
        }
        return rows.copy(
            templates = templates,
            notes = rows.notes.orEmpty().mapNotNull { n -> if (n.id in translated) translated[n.id] else n },
        )
    }

    private fun refs(side: String, fields: Map<String, Int>): List<Ref> = TOKEN.findAll(side).mapNotNull { m ->
        val inner = m.groupValues[1].trim()
        when {
            m.groupValues[1].isEmpty() -> DIVIDER

            inner.isEmpty() || inner.first() in "#^/!" -> null

            else -> {
                val parts = inner.split(':').map { it.trim() }
                val name = parts.last()
                val filters = parts.dropLast(1).map { it.substringBefore(' ') }.toSet()
                if (name == "FrontSide") FRONT_SIDE else fields[name]?.let { Ref(it, filters) }
            }
        }
    }.toList()

    /** The Rondo template for [t] and its notes by id, or null when it doesn't fit. */
    private fun translate(t: Template, notes: List<Note>): Pair<Template, Map<String, Note>>? {
        val names = t.def.fields.associate { it.name to it.id }
        val sides = t.def.cards.map {
            Triple(it, refs(it.ankiFront.orEmpty(), names), refs(it.ankiBack.orEmpty(), names))
        }
        val all = sides.flatMap { it.second + it.third }.filter { it.field > 0 }
        val filters = all.groupBy({ it.field }, { it.filters }).mapValues { (_, f) -> f.flatten().toSet() }
        val each = all.firstOrNull { "cloze" in it.filters }?.field
        val furigana = filters.filterValues { f -> f.any { it == "furigana" || it == "kanji" || it == "kana" } }.keys
        val converted = notes.associate { n ->
            n.id to n.fields.mapValues { (k, v) -> Html(k.toIntOrNull() in furigana).convert(v) }
        }
        if (each != null && notes.any { SHAPE.containsMatchIn(it.fields[each.toString()].orEmpty()) }) {
            return occlusion(t, notes, converted, each)
        }

        // A field that only ever held images or sounds becomes one; text with media gets media fields beside it.
        val fields = ArrayList<FieldDef>()
        val extras = HashMap<Int, List<FieldDef>>()
        var next = t.def.fields.maxOf { it.id }
        for (f in t.def.fields) {
            val values = converted.values.mapNotNull { it[f.id.toString()] }
            val image = values.any { it.images.isNotEmpty() }
            val sound = values.any { it.sounds.isNotEmpty() }
            val kind = when {
                f.id == each || values.any { it.markup.isNotBlank() } || (!image && !sound) -> TEXT
                image -> IMAGE
                else -> AUDIO
            }
            val added = listOfNotNull(
                if (image && kind != IMAGE) FieldDef(++next, "${f.name} image", IMAGE) else null,
                if (sound && kind != AUDIO) FieldDef(++next, "${f.name} audio", AUDIO) else null,
            )
            fields += FieldDef(f.id, f.name, kind, if ("tts" in filters[f.id].orEmpty()) true else null)
            fields += added
            extras[f.id] = added
        }
        val kinds = fields.associate { it.id to it.kind }
        fun blocks(r: Ref, large: Boolean): List<Block> = when {
            r === DIVIDER -> listOf(Block(divider = true))

            "type" in r.filters && "cloze" !in r.filters ->
                if (kinds[r.field] == TEXT) listOf(Block(typein = r.field)) else emptyList()

            else -> {
                val size = if (large) Block.PropertySize.LARGE else null
                val media = extras[r.field].orEmpty().map { Block(field = it.id) }
                listOf(Block(field = r.field, propertySize = size)) + media
            }
        }
        val cards = sides.map { (c, front, back) ->
            val lead = front.firstOrNull { it.field > 0 && "type" !in it.filters }?.takeIf { each == null }
            val shown = front.filter { it.field > 0 }.flatMap { blocks(it, it === lead) }
                .ifEmpty { listOf(Block(field = fields.first().id)) }
            val answer = back.flatMap { if (it === FRONT_SIDE) shown else blocks(it, false) }
            CardDef(c.id, c.name, shown, answer, each = each)
        }
        val def = TemplateDef(fields, cards)
        val builtin = Templates.builtins.firstOrNull { shape(it.def) == shape(def) }
        val target = builtin ?: t.copy(kind = Templates.RONDO, def = def)
        if (Templates.problem(target) != null) return null
        return target to notes.associate { n ->
            val c = converted.getValue(n.id)
            val values = HashMap<String, String>()
            for (f in t.def.fields) {
                val v = c[f.id.toString()] ?: continue
                when (kinds.getValue(f.id)) {
                    IMAGE -> v.images.firstOrNull()
                    AUDIO -> v.sounds.firstOrNull()
                    else -> v.markup.takeIf { it.isNotBlank() }
                }?.let { values[f.id.toString()] = it }
                for (x in extras[f.id].orEmpty()) {
                    val media = if (x.kind == IMAGE) v.images else v.sounds
                    media.firstOrNull()?.let { values[x.id.toString()] = it }
                }
            }
            n.id to n.copy(templateId = target.id, fields = values)
        }
    }

    private fun shape(d: TemplateDef) = d.fields.map { it.kind } to d.cards.map { c ->
        fun blocks(list: List<Block>) = list.map { listOf(it.field, it.typein, it.divider, it.label) }
        listOf(c.id, c.each, blocks(c.front), blocks(c.back))
    }

    /** Anki's image occlusion notes, on the built-in template: the image with its masks, a header and notes. */
    private fun occlusion(
        t: Template,
        notes: List<Note>,
        converted: Map<String, Map<String, Converted>>,
        each: Int,
    ): Pair<Template, Map<String, Note>> {
        val others = t.def.fields.map { it.id }.filter { it != each }
        return Templates.builtin(Templates.OCCLUSION)!! to notes.mapNotNull { n ->
            val c = converted.getValue(n.id)
            val imageField = others.firstOrNull { c[it.toString()]?.images?.isNotEmpty() == true }
                ?: return@mapNotNull null
            val masks = SHAPE.findAll(n.fields[each.toString()].orEmpty()).mapNotNull {
                rect(it.groupValues[1].toInt(), it.groupValues[2], it.groupValues[3])
            }.toList()
            if (masks.isEmpty()) return@mapNotNull null
            val text = others.filter { it != imageField }.map { c[it.toString()]?.markup.orEmpty() }
            val fields = mapOf(
                "1" to Occlusion.write(c.getValue(imageField.toString()).images.first(), masks),
                "2" to text.firstOrNull().orEmpty(),
                "3" to text.drop(1).filter { it.isNotBlank() }.joinToString("\n\n"),
            )
            n.id to n.copy(templateId = Templates.OCCLUSION, fields = fields.filterValues { it.isNotBlank() })
        }.toMap()
    }

    /** One shape's bounding box: rectangles, ellipses and polygons, in fractions of the image. */
    private fun rect(group: Int, shape: String, props: String): Occlusion.Rect? {
        val p = props.split(':').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        fun d(key: String) = p[key]?.toDoubleOrNull()
        val box = when (shape) {
            "rect" -> listOf(d("left"), d("top"), d("width"), d("height"))

            "ellipse" -> listOf(d("left"), d("top"), d("rx")?.times(2), d("ry")?.times(2))

            else -> {
                val points = p["points"].orEmpty().trim().split(' ').map { pt ->
                    pt.split(',').map { it.toDoubleOrNull() ?: return null }
                }
                if (points.any { it.size != 2 }) return null
                val xs = points.map { it[0] }
                val ys = points.map { it[1] }
                listOf(xs.min(), ys.min(), xs.max() - xs.min(), ys.max() - ys.min())
            }
        }.map { it ?: return null }
        val (x, y, w, h) = box
        if (box.any { it !in 0.0..1.0 } || group !in 1..999) return null
        return Occlusion.Rect(group, x, y, w.coerceAtMost(1 - x), h.coerceAtMost(1 - y))
    }
}

/** One field's HTML as markup, with the media it showed (hashes from `rondo-media:` links). */
class Converted(val markup: String, val images: List<String>, val sounds: List<String>)

/** Anki's field HTML read as markup: formatting, lines, lists, tables, ruby, clozes and MathJax. */
private class Html(private val furigana: Boolean) {
    private val blocks = ArrayList<TextBlock>()
    private var runs = ArrayList<Run>()
    private var marks = 0
    private var cloze = 0
    private var clozeStart = 0
    private var hint: StringBuilder? = null
    private var math: StringBuilder? = null
    private var mathEnd = ""
    private var nested = 0
    private val images = ArrayList<String>()
    private val sounds = ArrayList<String>()

    fun convert(html: String): Converted {
        Ksoup.parseBodyFragment(html).body().childNodes().forEach(::node)
        math?.let { add(it.toString()) }
        paragraph()
        return Converted(Markup.write(blocks), images, sounds)
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
                    Markup.block(TextBlock.TABLE, rows = rows.filter { it.isNotEmpty() }, header = header)
                } else {
                    val kind = if (tag == "ul") TextBlock.BULLETS else TextBlock.NUMBERS
                    Markup.block(kind, items = n.children().filter { it.normalName() == "li" }.map(::inline))
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
        return runs.map { if (it.kind == Run.BREAK) Markup.run(" ") else it }.also { runs = outer }
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
                    runs[first] = runs[first].let { Markup.run(it.text, it.marks, it.kind, it.reading, it.cloze, h) }
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
            blocks += Markup.block(TextBlock.MATH, tex = tex)
        } else {
            runs += Markup.run(tex, marks, Run.MATH, cloze = cloze)
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
        if (text.isNotEmpty()) runs += Markup.run(text, marks, kind, reading, cloze)
    }

    private fun newline(force: Boolean = false) {
        (math ?: hint)?.let {
            it.append(' ')
            return
        }
        trimEnd()
        if (runs.isNotEmpty() && (force || runs.last().kind != Run.BREAK)) runs += Markup.run("", kind = Run.BREAK)
    }

    private fun trimEnd() {
        val last = runs.lastOrNull()?.takeIf { it.kind == Run.TEXT && it.text.last().isWhitespace() } ?: return
        val text = last.text.trimEnd()
        if (text.isEmpty()) {
            runs.removeAt(runs.lastIndex)
        } else {
            runs[runs.lastIndex] = Markup.run(text, last.marks, last.kind, last.reading, last.cloze, last.hint)
        }
    }

    private fun paragraph() {
        trimEnd()
        while (runs.lastOrNull()?.kind == Run.BREAK) runs.removeAt(runs.lastIndex)
        if (runs.isNotEmpty()) blocks += Markup.block(TextBlock.PARAGRAPH, runs.toList())
        runs = ArrayList()
    }

    companion object {
        private val TOKENS = Regex("""\{\{c(\d+)::|::|\}\}|\\[()\[\]]|\[/?\$\$?\]|\[/?latex\]|\[sound:([^\]]+)\]""")
        private val MATH = mapOf(
            "\\(" to "\\)",
            "\\[" to "\\]",
            "[$]" to "[/$]",
            "[$$]" to "[/$$]",
            "[latex]" to "[/latex]",
        )
        private val FURIGANA = Regex(""" ?([^ \[\]]+)\[([^\]]+)\]""")
        private val MEDIA = Regex("rondo-media:([0-9a-f]{64})")
        private val SPACES = Regex("\\s{2,}")
        private val BOLD = Regex("font-weight:\\s*(bold|[6-9]00)")
        private val HIGHLIGHT =
            Regex("background(-color)?:\\s*(?!transparent|inherit|initial|none|white|#fff|rgba?\\(255,\\s*255,\\s*255)")
        private val MARKS = mapOf(
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
