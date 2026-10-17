package app.rondo.client.anki

import app.rondo.core.files.domain.Contents
import app.rondo.core.markup.domain.Markup
import app.rondo.core.notes.domain.Block
import app.rondo.core.notes.domain.FieldDef
import app.rondo.core.notes.domain.RondoTemplate
import app.rondo.core.notes.domain.Template

/**
 * A change offered on top of an exact conversion, with how likely it's wanted ([chance], 0 to 1):
 * the person sees each one, and those likely enough start chosen. Each works on Rondo's own data,
 * so it knows nothing of where it came from.
 */
sealed class Suggestion(val chance: Double) {
    /** Whether it starts chosen. */
    val likely: Boolean get() = chance >= LIKELY

    /** [contents] with this applied. */
    abstract fun apply(contents: List<Contents>): List<Contents>

    /** Notes of [template] titled by field [field] ([name]): it goes first, so lists and duplicates go by it. */
    class Title(val template: String, val field: Int, val name: String, chance: Double) : Suggestion(chance) {
        override fun apply(contents: List<Contents>) = templates(contents, template) { t ->
            t.copy(fields = t.fields.sortedBy { it.id != field })
        }
    }

    /** Field [field] ([name]) of [template] large wherever it shows. */
    class Large(val template: String, val field: Int, val name: String, chance: Double) : Suggestion(chance) {
        override fun apply(contents: List<Contents>) = templates(contents, template) { t ->
            fun sized(blocks: List<Block>) = blocks.map {
                if (it is Block.Field &&
                    it.id == field
                ) {
                    it.copy(size = Block.Size.LARGE)
                } else {
                    it
                }
            }
            t.copy(cards = t.cards.map { it.copy(front = sized(it.front), back = sized(it.back)) })
        }
    }

    /** Deck [deck] ([name]) and what's under it in [language] (BCP 47); [fields] of each note type in it read aloud. */
    class Language(
        val deck: String,
        val name: String,
        val language: String,
        val fields: Map<String, Set<Int>>,
        chance: Double,
    ) : Suggestion(chance) {
        override fun apply(contents: List<Contents>): List<Contents> {
            val spoken = templates(contents, null) { t ->
                val own = fields[t.id] ?: return@templates t
                t.copy(fields = t.fields.map { if (it.id in own) it.copy(tts = true) else it })
            }
            return spoken.map { c -> c.map(deck = { if (it.id == deck) it.copy(language = language) else it }) }
        }
    }

    private companion object {
        /** How likely a suggestion must be to start chosen. */
        const val LIKELY = 0.75

        /** [contents] with each of its own note types [id] (all, when null) changed by [change]. */
        fun templates(contents: List<Contents>, id: String?, change: (RondoTemplate) -> RondoTemplate): List<Contents> {
            val changed = HashMap<String, Template>()
            fun of(t: Template): Template = changed.getOrPut(t.id) {
                if (t is RondoTemplate && !t.builtin && (id == null || t.id == id)) change(t) else t
            }
            return contents.map { c -> c.map(entry = { Contents.Entry(it.note, of(it.template), it.events) }) }
        }
    }
}

/**
 * What [contents] (the exact conversion's) could use, found by looking at them: a title for notes
 * whose first field no card shows, a field to make large when nothing is, and the language of each
 * deck at the top, told by the script its notes' titles are written in.
 */
class Suggestions(contents: List<Contents>) {
    val all: List<Suggestion>

    init {
        val entries = contents.flatMap { c -> c.all.flatMap { it.notes } }
        val templates = entries.map {
            it.template
        }.filterIsInstance<RondoTemplate>().filter { !it.builtin }.distinctBy { it.id }
        val out = ArrayList<Suggestion>()
        for (t in templates) {
            val shown = t.cards.flatMap { it.front + it.back }.mapNotNull {
                (it as? Block.Field)?.id
                    ?: (it as? Block.TypeIn)?.id
            }.toSet()
            val asked = lead(t)
            val first = t.fields.first()
            // The first field shows on no card (Anki's sort field, a key): the first card's question titles the notes.
            if (first.id !in shown && asked != null &&
                asked.id != first.id
            ) {
                out += Suggestion.Title(t.id, asked.id, asked.name, 0.9)
            }
            val large = t.cards.flatMap { it.front + it.back }.any { it is Block.Field && it.size == Block.Size.LARGE }
            if (!large && asked != null &&
                t.cards.none { it.each != null }
            ) {
                out += Suggestion.Large(t.id, asked.id, asked.name, 0.8)
            }
        }
        for (c in contents) {
            val notes = c.all.flatMap { it.notes }
            val titles = notes.mapNotNull { e ->
                lead(e.template)?.let { e.note.fields[it.id.toString()] }?.let { Markup(it).plain }
            }
                .filter { it.isNotBlank() }
            val told = titles.map(::script)
            val top = told.filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value } ?: continue
            val texts = notes.flatMap { e ->
                e.template.fields.filter {
                    it.kind == FieldDef.Kind.TEXT
                }.mapNotNull { e.note.fields[it.id.toString()] }
            }
            val language = language(top.key, texts) ?: continue
            // The fields mostly written in the language: those read aloud, and spelled as they are.
            val fields = notes.groupBy { it.template.id }.mapValues { (_, es) ->
                es.first().template.fields.filter { it.kind == FieldDef.Kind.TEXT }.filter { f ->
                    val written = es.mapNotNull {
                        it.note.fields[f.id.toString()]
                    }.map { Markup(it).plain }.filter { it.isNotBlank() }
                    written.isNotEmpty() && written.count { script(it) == top.key } * 10 >= written.size * 9
                }.map { it.id }.toSet()
            }
            out += Suggestion.Language(c.deck.id, c.deck.name, language, fields, top.value.toDouble() / told.size)
        }
        all = out
    }

    /** The text field the first card asks first. */
    private fun lead(t: Template): FieldDef? {
        val front = (t as? RondoTemplate)?.cards?.firstOrNull()?.front ?: return null
        val ids = front.filterIsInstance<Block.Field>().map { it.id }
        return ids.firstNotNullOfOrNull { id -> t.fields.firstOrNull { it.id == id && it.kind == FieldDef.Kind.TEXT } }
    }

    /** The script most of [text]'s letters are in (kana counting as Han); null for Latin, or none. */
    private fun script(text: String): String? {
        val counts = text.mapNotNull { c ->
            when (c.code) {
                in 0x3040..0x30FF, in 0x31F0..0x31FF, in 0xFF66..0xFF9D -> "han"
                in 0x4E00..0x9FFF, in 0x3400..0x4DBF, in 0xF900..0xFAFF -> "han"
                in 0xAC00..0xD7AF, in 0x1100..0x11FF, in 0x3130..0x318F -> "ko"
                in 0x0400..0x04FF -> "cyrillic"
                in 0x0370..0x03FF -> "el"
                in 0x0600..0x06FF, in 0x0750..0x077F -> "arabic"
                in 0x0590..0x05FF -> "he"
                in 0x0E00..0x0E7F -> "th"
                in 0x0900..0x097F -> "hi"
                in 0x10A0..0x10FF -> "ka"
                in 0x0530..0x058F -> "hy"
                else -> if (c.isLetter()) "latin" else null
            }
        }.groupingBy { it }.eachCount()
        return counts.maxByOrNull {
            it.value
        }?.takeIf { (s, n) -> s != "latin" && n * 2 >= text.count { it.isLetter() } }?.key
    }

    /** The language written in [script]: [texts] (all of a deck's text) tell Japanese from Chinese, and the like. */
    private fun language(script: String, texts: List<String>): String? {
        val all = texts.joinToString(" ")
        return when (script) {
            "han" -> if (texts.count { t -> t.any { it.code in 0x3040..0x30FF } } * 20 >= texts.size) "ja" else "zh"
            "cyrillic" -> if (all.any { it in "іїєґІЇЄҐ" }) "uk" else "ru"
            "arabic" -> if (all.any { it in "پچژگ" }) "fa" else "ar"
            else -> script
        }
    }
}
