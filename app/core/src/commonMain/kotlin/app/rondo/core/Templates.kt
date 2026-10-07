package app.rondo.core

import app.rondo.core.model.Block
import app.rondo.core.model.CardDef
import app.rondo.core.model.FieldDef
import app.rondo.core.model.Note
import app.rondo.core.model.Template
import app.rondo.core.model.TemplateDef
import kotlin.js.JsExport

/** One thing drawn on a card side. [kind]: text, image, audio, occlusion, divider, label or typein. */
@JsExport
class Piece internal constructor(
    val kind: String,
    val size: String = "normal",
    val center: Boolean = true,
    val blocks: Array<TextBlock> = emptyArray(),
    val media: String? = null,
    val masks: Array<Mask> = emptyArray(),
    /** A label's text, or the expected answer of a type-in. */
    val text: String? = null,
    /** Plain text to speak: the field is in the deck's language. */
    val speech: String? = null,
    /** The field is in the deck's language (its spelling and voice), not the learner's. */
    val foreign: Boolean = false,
)

/** An occlusion mask, in fractions of the image. */
@JsExport
class Mask internal constructor(
    val group: Int,
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val active: Boolean,
    val hidden: Boolean,
)

/** A laid-out side of a card: pieces for Rondo templates, HTML for Anki 1:1 templates. */
@JsExport
class CardSide internal constructor(val pieces: Array<Piece>, val html: String? = null, val css: String? = null)

object Templates {
    const val RONDO = 1
    const val ANKI = 2

    val BASIC = Ids.builtin(1)
    val REVERSE = Ids.builtin(2)
    val TYPE = Ids.builtin(3)
    val CLOZE = Ids.builtin(4)
    val OCCLUSION = Ids.builtin(5)
    val VOCABULARY = Ids.builtin(6)

    private fun f(id: Int, name: String, kind: FieldDef.Kind = FieldDef.Kind.TEXT, tts: Boolean? = null) =
        FieldDef(id, name, kind, tts)
    private fun b(field: Int, size: Block.PropertySize? = null) = Block(field = field, propertySize = size)
    private val LARGE = Block.PropertySize.LARGE
    private val SMALL = Block.PropertySize.SMALL
    private val DIVIDER = Block(divider = true)
    private fun builtin(id: String, name: String, def: TemplateDef) = Template(id, name, RONDO, def, 0)

    val builtins: List<Template> = listOf(
        builtin(
            BASIC,
            "Basic",
            TemplateDef(
                listOf(f(1, "Front", tts = true), f(2, "Back")),
                listOf(CardDef(1, "Card", listOf(b(1, LARGE)), listOf(b(1, LARGE), DIVIDER, b(2)))),
            ),
        ),
        builtin(
            REVERSE,
            "Basic and reversed",
            TemplateDef(
                listOf(f(1, "Front", tts = true), f(2, "Back")),
                listOf(
                    CardDef(1, "Forward", listOf(b(1, LARGE)), listOf(b(1, LARGE), DIVIDER, b(2))),
                    CardDef(2, "Reverse", listOf(b(2, LARGE)), listOf(b(2, LARGE), DIVIDER, b(1))),
                ),
            ),
        ),
        builtin(
            TYPE,
            "Type the answer",
            TemplateDef(
                listOf(f(1, "Front", tts = true), f(2, "Back")),
                listOf(
                    CardDef(
                        1,
                        "Card",
                        listOf(b(1, LARGE), Block(typein = 2)),
                        listOf(b(1, LARGE), Block(typein = 2), DIVIDER, b(2)),
                    ),
                ),
            ),
        ),
        builtin(
            CLOZE,
            "Cloze",
            TemplateDef(
                listOf(f(1, "Text"), f(2, "Extra")),
                listOf(CardDef(1, "Cloze", listOf(b(1)), listOf(b(1), b(2, SMALL)), each = 1)),
            ),
        ),
        builtin(
            OCCLUSION,
            "Image occlusion",
            TemplateDef(
                listOf(f(1, "Image", FieldDef.Kind.OCCLUSION), f(2, "Header"), f(3, "Notes")),
                listOf(CardDef(1, "Mask", listOf(b(2), b(1)), listOf(b(2), b(1), b(3, SMALL)), each = 1)),
            ),
        ),
        builtin(
            VOCABULARY,
            "Vocabulary",
            TemplateDef(
                listOf(
                    f(1, "Word", tts = true),
                    f(2, "Reading"),
                    f(3, "Meaning"),
                    f(4, "Audio", FieldDef.Kind.AUDIO),
                    f(5, "Example", tts = true),
                ),
                listOf(
                    CardDef(
                        1,
                        "Recognize",
                        listOf(b(1, LARGE)),
                        listOf(b(1, LARGE), b(2), DIVIDER, b(3), b(4), b(5, SMALL)),
                    ),
                    CardDef(
                        2,
                        "Recall",
                        listOf(b(3, LARGE)),
                        listOf(b(3, LARGE), DIVIDER, b(1, LARGE), b(2), b(4), b(5, SMALL)),
                    ),
                ),
            ),
        ),
    )

    fun builtin(id: String): Template? = builtins.firstOrNull { it.id == id }

    fun isBuiltin(id: String): Boolean = builtin(id) != null

    fun textFields(t: Template): Set<String> =
        t.def.fields.filter { it.kind == FieldDef.Kind.TEXT }.map { it.id.toString() }.toSet()

    // Validation ---------------------------------------------------------------------------------

    /** Why [t]'s definition is invalid, or null. */
    fun problem(t: Template): String? {
        val d = t.def
        if (t.name.isBlank() || t.name.length > 100) return "name"
        if (d.fields.isEmpty() || d.fields.size > 32) return "field count"
        if (d.fields.map { it.id }.toSet().size != d.fields.size ||
            d.fields.any { it.id !in 1..999 || it.name.isBlank() }
        ) {
            return "field ids"
        }
        if (d.cards.isEmpty() || d.cards.size > 16) return "card count"
        if (d.cards.map { it.id }.toSet().size != d.cards.size || d.cards.any { it.id !in 1..99 }) return "card ids"
        val kinds = d.fields.associate { it.id to it.kind }
        if (t.kind == ANKI) {
            return if (d.cards.all { it.ankiFront != null && it.ankiBack != null }) null else "anki templates"
        }
        if (t.kind != RONDO) return "kind"
        for (c in d.cards) {
            if (c.front.isEmpty()) return "empty front"
            for (block in c.front + c.back) {
                val set = listOfNotNull(block.field, block.typein, block.divider?.takeIf { it }, block.label).size
                if (set != 1) return "block"
                if (block.field != null && block.field !in kinds) return "block field"
                if (block.typein != null && kinds[block.typein] != FieldDef.Kind.TEXT) return "type-in field"
            }
            if (c.each != null) {
                if (d.cards.size != 1) return "each with several cards"
                if (kinds[c.each] != FieldDef.Kind.TEXT && kinds[c.each] != FieldDef.Kind.OCCLUSION) return "each field"
            }
        }
        return null
    }

    /** Why a note's [fields] don't fit [t], or null. */
    fun fieldsProblem(t: Template, fields: Map<String, String>): String? {
        val kinds = t.def.fields.associate { it.id.toString() to it.kind }
        if (fields.keys.any { it !in kinds }) return "unknown field"
        if (fields.values.sumOf { it.length } > 65_536) return "too large"
        for ((key, value) in fields) {
            if (value.isEmpty() || t.kind == ANKI) continue
            when (kinds.getValue(key)) {
                FieldDef.Kind.IMAGE, FieldDef.Kind.AUDIO -> if (!HASH.matches(value)) return "media"
                FieldDef.Kind.OCCLUSION -> if (Occlusion.parse(value) == null) return "occlusion"
                FieldDef.Kind.TEXT -> Unit
            }
        }
        return null
    }

    val HASH = Regex("^[0-9a-f]{64}$")
    private val MEDIA_REF = Regex("rondo-media:([0-9a-f]{64})")

    /** The media files [note] uses: what keeps them stored. */
    fun media(t: Template, note: Note): Set<String> = t.def.fields.flatMap { f ->
        val value = note.fields[f.id.toString()].orEmpty()
        when {
            t.kind == ANKI -> MEDIA_REF.findAll(value).map { it.groupValues[1] }.toList()
            f.kind == FieldDef.Kind.OCCLUSION -> listOf(Occlusion.image(value))
            f.kind != FieldDef.Kind.TEXT -> listOf(value)
            else -> emptyList()
        }
    }.filter { HASH.matches(it) }.toSet()

    // Cards --------------------------------------------------------------------------------------

    /** The card numbers [note] has under [t]. */
    fun cards(t: Template, note: Note): List<Int> {
        val each = t.def.cards.singleOrNull()?.each
        if (t.kind == ANKI) return Anki.cards(t, note)
        if (each != null) {
            val value = note.fields[each.toString()].orEmpty()
            val kind = t.def.fields.firstOrNull { it.id == each }?.kind
            return if (kind == FieldDef.Kind.OCCLUSION) {
                Occlusion.parse(value)?.map { it.group }?.distinct()?.sorted().orEmpty()
            } else {
                Markup.clozes(value).toList()
            }
        }
        return t.def.cards.filter { c ->
            c.front.mapNotNull { it.field }.all { note.fields[it.toString()].orEmpty().isNotBlank() }
        }.map { it.id }
    }

    /** Lays out one side of card [card] of [note]. */
    fun layout(t: Template, note: Note, card: Int, back: Boolean): CardSide {
        if (t.kind == ANKI) return Anki.layout(t, note, card, back)
        // A template with `each` has one card definition, for all its cards (problem() makes sure).
        val def = t.def.cards.firstOrNull { it.each != null || it.id == card } ?: return CardSide(emptyArray())
        val fields = t.def.fields.associateBy { it.id }
        val pieces = (if (back) def.back else def.front).mapNotNull { block ->
            val size = block.propertySize?.value ?: "normal"
            val center = block.align != Block.Align.START
            when {
                block.divider == true -> Piece("divider")

                block.label != null -> Piece("label", size, center, text = block.label)

                block.typein != null -> Piece(
                    "typein",
                    size,
                    center,
                    text = Markup.plain(note.fields[block.typein.toString()].orEmpty()),
                )

                else -> {
                    val field = fields[block.field] ?: return@mapNotNull null
                    val value = note.fields[field.id.toString()].orEmpty()
                    if (value.isBlank()) return@mapNotNull null
                    val active = if (def.each == field.id) card else 0
                    when (field.kind) {
                        FieldDef.Kind.TEXT -> Piece(
                            "text",
                            size,
                            center,
                            clozed(Markup.parse(value), active, back).toTypedArray(),
                            speech = if (field.tts == true && (back || active == 0)) Markup.plain(value) else null,
                            foreign = field.tts == true,
                        )

                        FieldDef.Kind.IMAGE -> Piece("image", size, center, media = value)

                        FieldDef.Kind.AUDIO -> Piece("audio", size, center, media = value)

                        FieldDef.Kind.OCCLUSION -> {
                            val masks = Occlusion.parse(value).orEmpty().map {
                                val isActive = it.group == active
                                Mask(it.group, it.x, it.y, it.w, it.h, active = isActive, hidden = !(back && isActive))
                            }
                            val image = Occlusion.image(value)
                            Piece("occlusion", size, center, media = image, masks = masks.toTypedArray())
                        }
                    }
                }
            }
        }
        return CardSide(pieces.toTypedArray())
    }

    /** Hides cloze [active] on the front, marks it on the back, and shows other clozes as text. */
    private fun clozed(blocks: List<TextBlock>, active: Int, back: Boolean): List<TextBlock> =
        Markup.mapRuns(blocks) { runs ->
            val out = ArrayList<Run>()
            for (r in runs) {
                when {
                    r.cloze == 0 || r.cloze != active -> out += Run(r.text, r.marks, r.kind, r.reading)

                    back -> out += Run(r.text, r.marks or Run.REVEALED, r.kind, r.reading, r.cloze)

                    out.lastOrNull()?.kind != Run.BLANK ->
                        out += Run(r.hint ?: "…", kind = Run.BLANK, cloze = r.cloze)
                }
            }
            out
        }
}

/** Image occlusion values: the image hash, then one mask per line: `group x y w h` (fractions). */
object Occlusion {
    class Rect(val group: Int, val x: Double, val y: Double, val w: Double, val h: Double)

    fun image(value: String): String = value.substringBefore('\n').trim()

    fun parse(value: String): List<Rect>? {
        val lines = value.split('\n')
        if (!Templates.HASH.matches(lines.first().trim())) return null
        return lines.drop(1).filter { it.isNotBlank() }.map { line ->
            val p = line.trim().split(' ')
            if (p.size != 5) return null
            val n = p.drop(1).map { it.toDoubleOrNull()?.takeIf { v -> v in 0.0..1.0 } ?: return null }
            Rect(p[0].toIntOrNull()?.takeIf { it in 1..999 } ?: return null, n[0], n[1], n[2], n[3])
        }
    }

    fun write(image: String, masks: List<Rect>): String {
        val lines = masks.map { "${it.group} ${round4(it.x)} ${round4(it.y)} ${round4(it.w)} ${round4(it.h)}" }
        return (listOf(image) + lines).joinToString("\n")
    }

    private fun round4(v: Double): String = (kotlin.math.round(v * 10_000) / 10_000).toString()
}

/**
 * Anki's template language, enough for imported decks shown 1:1: fields, `{{#…}}`/`{{^…}}`
 * sections, `{{FrontSide}}`, and the cloze, text, hint, type and furigana filters.
 */
object Anki {
    private val TAG = Regex("<[^>]*>")
    private val CLOZE_FILTER = Regex("\\{\\{cloze:([^}]+)\\}\\}")
    private val CLOZE = Regex("\\{\\{c(\\d+)::([\\s\\S]*?)(?:::([\\s\\S]*?))?\\}\\}")
    private val FURIGANA = Regex(" ?([^ >\\[]+)\\[([^\\]]+)\\]")

    private fun named(t: Template, note: Note): Map<String, String> =
        t.def.fields.associate { it.name to note.fields[it.id.toString()].orEmpty() }

    fun cards(t: Template, note: Note): List<Int> {
        val card = t.def.cards.singleOrNull()
        val clozeField = card?.ankiFront?.let { CLOZE_FILTER.find(it)?.groupValues?.get(1) }
        if (clozeField != null) return Markup.clozes(named(t, note)[clozeField.trim()].orEmpty()).toList()
        val fields = named(t, note)
        return t.def.cards.filter { c ->
            render(c.ankiFront.orEmpty(), fields, 0, false, "").replace(TAG, "").isNotBlank()
        }.map { it.id }
    }

    fun layout(t: Template, note: Note, card: Int, back: Boolean): CardSide {
        val fields = named(t, note)
        val cards = t.def.cards
        val def = cards.singleOrNull() ?: cards.firstOrNull { it.id == card } ?: return CardSide(emptyArray())
        val front = render(def.ankiFront.orEmpty(), fields, card, false, "")
        val html = if (back) render(def.ankiBack.orEmpty(), fields, card, true, front) else front
        return CardSide(emptyArray(), html, t.def.css)
    }

    fun render(template: String, fields: Map<String, String>, card: Int, back: Boolean, frontSide: String): String {
        var out = template
        val section = Regex("\\{\\{([#^])([^}]+)\\}\\}([\\s\\S]*?)\\{\\{/\\2\\}\\}")
        while (true) {
            val m = section.find(out) ?: break
            val filled = fields[m.groupValues[2].trim()].orEmpty().replace(TAG, "").isNotBlank()
            out = out.replaceRange(m.range, if (filled == (m.groupValues[1] == "#")) m.groupValues[3] else "")
        }
        return Regex("\\{\\{([^}]+)\\}\\}").replace(out) { m ->
            val parts = m.groupValues[1].trim().split(':')
            val name = parts.last().trim()
            val value = if (name == "FrontSide") frontSide else fields[name].orEmpty()
            parts.dropLast(1).map { it.trim() }.foldRight(value) { filter, v -> filter(filter, v, card, back) }
        }
    }

    private fun filter(name: String, value: String, card: Int, back: Boolean): String = when (name) {
        "cloze" -> CLOZE.replace(value) { m ->
            val n = m.groupValues[1].toInt()
            when {
                n != card -> m.groupValues[2]
                back -> "<span class=\"cloze\">${m.groupValues[2]}</span>"
                else -> "<span class=\"cloze\">[${m.groupValues[3].ifEmpty { "…" }}]</span>"
            }
        }

        "text" -> value.replace(TAG, "")

        "type" -> ""

        "hint" -> value

        "furigana" -> FURIGANA.replace(value) { "<ruby>${it.groupValues[1]}<rt>${it.groupValues[2]}</rt></ruby>" }

        "kanji" -> FURIGANA.replace(value) { it.groupValues[1] }

        "kana" -> FURIGANA.replace(value) { it.groupValues[2] }

        else -> value
    }
}
