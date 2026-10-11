package app.rondo.client.anki

import app.rondo.client.storage.Derived
import app.rondo.core.AnkiSide
import app.rondo.core.AnkiTemplate
import app.rondo.core.Block
import app.rondo.core.CardDef
import app.rondo.core.FieldDef
import app.rondo.core.Note
import app.rondo.core.Occlusion
import app.rondo.core.RondoTemplate
import app.rondo.core.Template
import app.rondo.core.file.Contents

/**
 * Anki's note types in [contents] made Rondo's own, exactly: only what Anki's format says. Fields
 * keep Anki's names, order and ids, and each picture or sound beyond a field's first gets a field
 * of its own after it. Each side's blocks follow its template: the fields it shows in order,
 * `{{FrontSide}}` as the front's, `{{type:}}` a box to type in, every rule a divider, one card per
 * cloze. A card keeps Anki's rule for when it exists. A note type just like one of Rondo's own
 * becomes it. Nothing is guessed: sizes, titles and languages are [Suggestions].
 *
 * Then it's checked, and a note type that fails keeps Anki's look ([kept]): every note must have
 * the same cards as in Anki (less any whose front would show nothing), and fit Rondo's rules.
 */
class AnkiConversion(contents: List<Contents>) {
    /** Why a note type keeps Anki's look. */
    enum class Kept {
        /** Rondo would make other cards from its notes. */
        CARDS,

        /** Its cards are laid out in a way Rondo's can't follow. */
        LAYOUT,

        /** A note holds what a Rondo note can't. */
        CONTENT,
    }

    /** [contents] with each Anki note type that converts made Rondo's, its notes with it. */
    val contents: List<Contents>

    /** The note types (by their id in [contents] as given) that keep Anki's look, and why. */
    val kept: Map<String, Kept>

    init {
        val entries = contents.flatMap { c -> c.all.flatMap { it.notes } }
        val converted = HashMap<String, Pair<Template, Map<String, Note>>>()
        val kept = HashMap<String, Kept>()
        for ((t, list) in entries.groupBy { it.template.id }.mapNotNull { (_, es) ->
            (es.first().template as? AnkiTemplate)?.let {
                it to
                    es.map { e -> e.note }
            }
        }) {
            when (val r = Translation(t, list).result) {
                is Result.Done -> converted[t.id] = r.template to r.notes
                is Result.Failed -> kept[t.id] = r.reason
            }
        }
        this.contents = contents.map { c ->
            c.map(
                entry = { e ->
                    converted[e.template.id]?.let { (template, notes) ->
                        Contents.Entry(notes.getValue(e.note.id), template, e.events)
                    }
                        ?: e
                },
            )
        }
        this.kept = kept
    }

    private sealed interface Result {
        class Done(val template: Template, val notes: Map<String, Note>) : Result

        class Failed(val reason: Kept) : Result
    }

    /**
     * A Rondo field made from an Anki one ([source], its id there): its text, or (when it's never
     * text) its first picture or sound; [n] counts the pictures or sounds beyond.
     */
    private class Part(val source: Int, val id: Int, val kind: FieldDef.Kind, val n: Int, val name: String) {
        fun value(html: AnkiHtml): String? = when (kind) {
            FieldDef.Kind.IMAGE -> html.images.getOrNull(n)
            FieldDef.Kind.AUDIO -> html.sounds.getOrNull(n)
            else -> html.markup.takeIf { it.isNotBlank() }
        }
    }

    /** One Anki note type [t] and its [notes] made Rondo's, or why not. */
    private class Translation(private val t: AnkiTemplate, private val notes: List<Note>) {
        private val names = t.fields.associate { it.name to it.id }
        private val sides = t.cards.map { it.front to it.back }

        /** Fields shown with a furigana filter: their `漢字[かんじ]` is ruby. */
        private val furigana = sides.flatMap { (f, b) -> f.fields() + b.fields() }
            .filter { s -> s.filters.any { it.substringBefore(' ') in FURIGANA } }.mapNotNull { names[it.name] }.toSet()
        private val html = notes.associate { n ->
            n.id to
                n.fields.mapValues { (k, v) -> AnkiHtml(v, k.toIntOrNull() in furigana) }
        }

        /** The field whose clozes make the cards, for Anki's cloze note types. */
        private val each = t.clozeField?.let(names::get)

        val result: Result = when {
            each != null && notes.any { SHAPE.containsMatchIn(it.fields[each.toString()].orEmpty()) } -> occlusion(each)
            else -> translate()
        }

        private fun translate(): Result {
            // Each field's parts: its text (or its only kind of media), then a field per picture and sound beyond.
            var next = t.fields.maxOf { it.id }
            val parts = t.fields.associate { f ->
                val v = html.values.mapNotNull { it[f.id.toString()] }
                val images = v.maxOfOrNull { it.images.size } ?: 0
                val sounds = v.maxOfOrNull { it.sounds.size } ?: 0
                val main = when {
                    f.id == each || v.any {
                        it.markup.isNotBlank()
                    } || (images == 0 && sounds == 0) -> FieldDef.Kind.TEXT

                    images > 0 -> FieldDef.Kind.IMAGE

                    else -> FieldDef.Kind.AUDIO
                }
                val name = f.name.trim().ifEmpty { "Field ${f.id}" }
                fun media(kind: FieldDef.Kind, count: Int, word: String) = (0 until count).mapNotNull { i ->
                    when {
                        main == kind && i == 0 -> null
                        main == kind -> Part(f.id, ++next, kind, i, "$name ${i + 1}")
                        else -> Part(f.id, ++next, kind, i, if (i == 0) "$name $word" else "$name $word ${i + 1}")
                    }
                }
                f.id to listOf(Part(f.id, f.id, main, 0, name)) + media(FieldDef.Kind.IMAGE, images, "image") +
                    media(FieldDef.Kind.AUDIO, sounds, "audio")
            }
            val all = t.fields.flatMap { parts.getValue(it.id) }

            // A field inside a tag (`<img src="{{Picture}}">`) can't be a block of its own.
            val attributes = sides.flatMap { (f, b) -> inAttributes(f) + inAttributes(b) }.mapNotNull(names::get)
            if (attributes.any { a -> html.values.any { it[a.toString()]?.markup?.isNotBlank() == true } }) {
                return Result.Failed(Kept.LAYOUT)
            }

            fun blocks(side: AnkiSide, front: List<Block>): List<Block> {
                val out = ArrayList<Block>()
                fun walk(nodes: List<AnkiSide.Node>) {
                    for (n in nodes) {
                        when (n) {
                            is AnkiSide.Text -> if (RULE.containsMatchIn(n.html) && out.isNotEmpty() &&
                                out.last() != Block.Divider
                            ) {
                                out += Block.Divider
                            }

                            is AnkiSide.Section -> walk(n.body)

                            is AnkiSide.Field -> when {
                                n.name == AnkiSide.FRONT_SIDE -> out += front

                                else -> {
                                    val own = names[n.name]?.let(parts::getValue) ?: continue
                                    if ("type" in n.filters && "cloze" !in n.filters) {
                                        if (own.first().kind == FieldDef.Kind.TEXT) out += Block.TypeIn(own.first().id)
                                    } else {
                                        own.forEach { out += Block.Field(it.id) }
                                    }
                                }
                            }
                        }
                    }
                }
                walk(side.nodes)
                while (out.lastOrNull() == Block.Divider) out.removeAt(out.lastIndex)
                return out
            }
            val cards = t.cards.mapNotNull { c ->
                val front = blocks(c.front, emptyList()).ifEmpty { listOf(Block.Field(all.first().id)) }
                val back = blocks(c.back, front)
                val requires = if (each != null) {
                    null
                } else {
                    // A field is filled when any of its parts is.
                    val groups = c.front.requirements(names.keys).flatMap { group ->
                        group.fold(listOf(emptySet<Int>())) { acc, name ->
                            acc.flatMap { g -> parts.getValue(names.getValue(name)).map { g + it.id } }
                        }
                    }
                    // Anki never makes this card.
                    if (groups.isEmpty()) return@mapNotNull null
                    if (groups.size > 64) return Result.Failed(Kept.LAYOUT)
                    val shown = front.filterIsInstance<Block.Field>().map { it.id }.toSet()
                    // Rondo's own rule, every field the front shows, needs nothing said.
                    groups.map { it.sorted() }.takeIf { it.size != 1 || it.single().toSet() != shown }
                }
                CardDef(c.id, c.name.trim().ifEmpty { "Card ${c.id}" }, front, back, each = each, requires = requires)
            }
            if (cards.isEmpty()) return Result.Failed(Kept.CARDS)

            val fields = all.map { FieldDef(it.id, it.name, it.kind) }
            val mine =
                RondoTemplate(Derived("rondo").of(t.id), t.name.trim().ifEmpty { "Anki" }, fields, cards, 0, t.ownerId)
            val target = Template.builtins.values.firstOrNull { shape(it) == shape(mine) } ?: mine
            val made = notes.associate { n ->
                val values = html.getValue(n.id)
                val f = all.mapNotNull { p ->
                    values[p.source.toString()]?.let(p::value)?.let { p.id.toString() to it }
                }.toMap()
                n.id to n.copy(templateId = target.id, fields = f)
            }
            return verify(target, made)
        }

        /** The fields shown inside tags' attributes on [side], which can't be blocks. */
        private fun inAttributes(side: AnkiSide): List<String> {
            val out = ArrayList<String>()
            val text = StringBuilder()
            fun walk(nodes: List<AnkiSide.Node>) {
                for (n in nodes) {
                    when (n) {
                        is AnkiSide.Text -> text.append(n.html)

                        is AnkiSide.Section -> walk(n.body)

                        is AnkiSide.Field -> if (text.lastIndexOf('<') >
                            text.lastIndexOf('>')
                        ) {
                            out += n.name
                        } else {
                            text.append("x")
                        }
                    }
                }
            }
            walk(side.nodes)
            return out
        }

        /** [made] if every note keeps its cards (less blank ones) and fits [target]; otherwise why not. */
        private fun verify(target: Template, made: Map<String, Note>): Result {
            if (target.problem != null) return Result.Failed(Kept.LAYOUT)
            for (n in notes) {
                val converted = made.getValue(n.id)
                if (target.problem(converted.fields) != null) return Result.Failed(Kept.CONTENT)
                val rondo = target.cards(converted).toSet()
                val anki = t.cards(n).toSet()
                if (!anki.containsAll(rondo)) return Result.Failed(Kept.CARDS)
                for (card in anki - rondo) {
                    val front = AnkiHtml(t.side(n, card, false).html.orEmpty())
                    if (front.markup.isNotBlank() || front.images.isNotEmpty() || front.sounds.isNotEmpty()) {
                        return Result.Failed(Kept.CARDS)
                    }
                }
            }
            return Result.Done(target, made)
        }

        /** Anki's image occlusion notes, on Rondo's own: the image with its masks, a header and notes. */
        private fun occlusion(each: Int): Result {
            val others = t.fields.map { it.id }.filter { it != each }
            val target = Template.OCCLUSION
            val out = HashMap<String, Note>()
            for (n in notes) {
                val c = html.getValue(n.id)
                val imageField = others.firstOrNull { c[it.toString()]?.images?.isNotEmpty() == true }
                    ?: return Result.Failed(Kept.CONTENT)
                val masks = SHAPE.findAll(n.fields[each.toString()].orEmpty()).mapNotNull {
                    rect(it.groupValues[1].toInt(), it.groupValues[2], it.groupValues[3])
                }.toList()
                val text = others.filter { it != imageField }.map { c[it.toString()]?.markup.orEmpty() }
                val fields = mapOf(
                    "1" to Occlusion(c.getValue(imageField.toString()).images.first(), masks).text,
                    "2" to text.firstOrNull().orEmpty(),
                    "3" to text.drop(1).filter { it.isNotBlank() }.joinToString("\n\n"),
                )
                out[n.id] = n.copy(templateId = target.id, fields = fields.filterValues { it.isNotBlank() })
            }
            return verify(target, out)
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

        /** What a note type's notes and cards are, sizes aside: how one of Rondo's own is recognised. */
        private fun shape(t: Template): Any? = (t as? RondoTemplate)?.let { r ->
            r.fields.map { it.id to it.kind } to r.cards.map { c ->
                fun blocks(list: List<Block>) = list.map { b -> if (b is Block.Field) Block.Field(b.id) else b }
                listOf(c.id, c.each, c.requires, blocks(c.front), blocks(c.back))
            }
        }
    }

    private companion object {
        val SHAPE = Regex("\\{\\{c(\\d+)::image-occlusion:(rect|ellipse|polygon):([^}]*)\\}\\}")
        val FURIGANA = setOf("furigana", "kanji", "kana")
        val RULE = Regex("<hr\\b", RegexOption.IGNORE_CASE)
    }
}
