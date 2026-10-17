package app.rondo.core.notes.domain

import app.rondo.core.decks.domain.Access
import app.rondo.core.decks.domain.Tree
import app.rondo.core.kernel.domain.ErrorCode
import app.rondo.core.markup.domain.Folded
import app.rondo.core.markup.domain.Markup
import app.rondo.core.markup.domain.Run
import app.rondo.core.markup.domain.TextBlock
import kotlin.uuid.Uuid

/**
 * A note type: its fields, and the cards each of its notes has. Rondo's own lay their sides out as
 * [Block]s ([RondoTemplate]); Anki's keep Anki's HTML and are shown 1:1, read-only ([AnkiTemplate]).
 */
sealed class Template {
    abstract val id: String
    abstract val name: String
    abstract val fields: List<FieldDef>
    abstract val v: Long
    abstract val ownerId: String?
    abstract val deletedAt: Long?

    /** One of Rondo's own, the same for everyone and never stored. */
    val builtin: Boolean get() = id in builtins

    /** The card numbers [note] has. */
    abstract fun cards(note: Note): List<Int>

    /** One side of card [card] of [note], laid out. */
    abstract fun side(note: Note, card: Int, back: Boolean): CardSide

    /** Why this definition is invalid, or null. */
    abstract val problem: String?

    /** This one with other clocks or owner: what saving and copying change. */
    abstract fun with(
        id: String = this.id,
        v: Long = this.v,
        ownerId: String? = this.ownerId,
        deletedAt: Long? = this.deletedAt,
    ): Template

    /** The media files [note] uses: what keeps them stored. */
    abstract fun media(note: Note): Set<String>

    /** Why a note's [fields] don't fit, or null. */
    open fun problem(fields: Map<String, String>): String? {
        val ids = this.fields.map { it.id.toString() }.toSet()
        if (fields.keys.any { it !in ids }) return "unknown field"
        if (fields.values.sumOf { it.length } > 65_536) return "too large"
        return null
    }

    /** [note] as lists name it: its first field, as plain text. */
    fun label(note: Note): String {
        val first = fields.firstOrNull()?.let { note.fields[it.id.toString()] }.orEmpty()
        return Markup(first).plain.take(80).ifBlank { "…" }
    }

    /** Its text fields' ids. */
    val textFields: Set<String> get() = fields.filter { it.kind == FieldDef.Kind.TEXT }.map { it.id.toString() }.toSet()

    /** The text [note] is found by: every text field, folded. */
    fun searchText(note: Note): String = note.fields.filterKeys { it in textFields }.values
        .joinToString(" ") { Folded(Markup(it).plain).text.replace('\n', ' ') }

    /**
     * What notes of this type repeat one another by: their first field, as plain text, case aside
     * and accents kept. Null when it's empty or isn't text.
     */
    fun key(fields: Map<String, String>): String? {
        val first = this.fields.firstOrNull()?.takeIf { it.kind == FieldDef.Kind.TEXT } ?: return null
        val text = Markup(fields[first.id.toString()].orEmpty()).plain
        return Folded(text, accents = true).text.split(SPACE).filter { it.isNotEmpty() }.joinToString(" ")
            .ifEmpty { null }
    }

    /**
     * The notes a note of this type in [deck] is compared with: its top-level deck and everything
     * under it, narrowed to those that could have [key] (compare their own key to be sure).
     */
    fun repeats(tree: Tree, deck: String, key: String? = null): NoteFilter {
        val root = tree.ancestors(deck).lastOrNull()?.id ?: deck
        return NoteFilter(key.orEmpty(), tree.subtree(root).map { it.id }, listOf(id))
    }

    /** Why [by] may not make this the note type that was [stored] (null: a new one), or null. */
    fun check(by: Access, stored: Template?): ErrorCode? {
        if (Uuid.parseOrNull(id) == null || builtin) return ErrorCode.INVALID
        if (stored != null) {
            if (stored.ownerId != ownerId) return ErrorCode.OWNER_MISMATCH
            if (stored.ownerId == null) return ErrorCode.READ_ONLY
            if (stored::class != this::class) return ErrorCode.INVALID
            // Anki's note types keep Anki's HTML exactly: only their name changes.
            val ankiChanged = this is AnkiTemplate && stored is AnkiTemplate &&
                (stored.fields != fields || stored.cards != cards || stored.css != css)
            if (ankiChanged) return ErrorCode.READ_ONLY
        }
        if (ownerId != by.actor) return ErrorCode.FORBIDDEN
        return if (problem != null) ErrorCode.INVALID else null
    }

    /** What every note type asks of its fields and cards. */
    protected fun shapeProblem(cardIds: List<Int>): String? {
        if (name.isBlank() || name.length > 100) return "name"
        if (fields.isEmpty() || fields.size > 32) return "field count"
        if (fields.map { it.id }.toSet().size != fields.size || fields.any { it.id !in 1..999 || it.name.isBlank() }) {
            return "field ids"
        }
        if (cardIds.isEmpty() || cardIds.size > 16) return "card count"
        if (cardIds.toSet().size != cardIds.size || cardIds.any { it !in 1..99 }) return "card ids"
        return null
    }

    companion object {
        /** A media file's name: its SHA-256, in hex. */
        val HASH = Regex("^[0-9a-f]{64}$")
        private val SPACE = Regex("\\s+")

        private fun f(id: Int, name: String, kind: FieldDef.Kind = FieldDef.Kind.TEXT, tts: Boolean = false) =
            FieldDef(id, name, kind, tts)
        private fun b(field: Int, size: Block.Size? = null) = Block.Field(field, size)
        private val LARGE = Block.Size.LARGE
        private val SMALL = Block.Size.SMALL
        private val DIVIDER = Block.Divider
        private fun builtin(n: Int) = "00000000-0000-7000-8000-" + n.toString().padStart(12, '0')

        val BASIC = RondoTemplate(
            builtin(1),
            "Basic",
            listOf(f(1, "Front", tts = true), f(2, "Back")),
            listOf(CardDef(1, "Card", listOf(b(1, LARGE)), listOf(b(1, LARGE), DIVIDER, b(2)))),
        )
        val REVERSE = RondoTemplate(
            builtin(2),
            "Basic and reversed",
            listOf(f(1, "Front", tts = true), f(2, "Back")),
            listOf(
                CardDef(1, "Forward", listOf(b(1, LARGE)), listOf(b(1, LARGE), DIVIDER, b(2))),
                CardDef(2, "Reverse", listOf(b(2, LARGE)), listOf(b(2, LARGE), DIVIDER, b(1))),
            ),
        )
        val TYPE = RondoTemplate(
            builtin(3),
            "Type the answer",
            listOf(f(1, "Front", tts = true), f(2, "Back")),
            listOf(
                CardDef(
                    1,
                    "Card",
                    listOf(b(1, LARGE), Block.TypeIn(2)),
                    listOf(b(1, LARGE), Block.TypeIn(2), DIVIDER, b(2)),
                ),
            ),
        )
        val CLOZE = RondoTemplate(
            builtin(4),
            "Cloze",
            listOf(f(1, "Text"), f(2, "Extra")),
            listOf(CardDef(1, "Cloze", listOf(b(1)), listOf(b(1), b(2, SMALL)), each = 1)),
        )
        val OCCLUSION = RondoTemplate(
            builtin(5),
            "Image occlusion",
            listOf(f(1, "Image", FieldDef.Kind.OCCLUSION), f(2, "Header"), f(3, "Notes")),
            listOf(CardDef(1, "Mask", listOf(b(2), b(1)), listOf(b(2), b(1), b(3, SMALL)), each = 1)),
        )
        val VOCABULARY = RondoTemplate(
            builtin(6),
            "Vocabulary",
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
        )

        /** Rondo's own note types, by id, in the order pickers list them. */
        val builtins: Map<String, RondoTemplate> =
            listOf(BASIC, REVERSE, TYPE, CLOZE, OCCLUSION, VOCABULARY).associateBy { it.id }
    }
}

/** One of Rondo's note types: each card's front and back are [Block]s. */
data class RondoTemplate(
    override val id: String,
    override val name: String,
    override val fields: List<FieldDef>,
    val cards: List<CardDef>,
    override val v: Long = 0,
    override val ownerId: String? = null,
    override val deletedAt: Long? = null,
) : Template() {
    override fun with(id: String, v: Long, ownerId: String?, deletedAt: Long?) =
        copy(id = id, v = v, ownerId = ownerId, deletedAt = deletedAt)

    override val problem: String?
        get() {
            shapeProblem(cards.map { it.id })?.let { return it }
            val kinds = fields.associate { it.id to it.kind }
            for (c in cards) {
                if (c.front.isEmpty()) return "empty front"
                for (block in c.front + c.back) {
                    if (block is Block.Field && block.id !in kinds) return "block field"
                    if (block is Block.TypeIn && kinds[block.id] != FieldDef.Kind.TEXT) return "type-in field"
                }
                if (c.each != null) {
                    if (cards.size != 1) return "each with several cards"
                    if (kinds[c.each] != FieldDef.Kind.TEXT &&
                        kinds[c.each] != FieldDef.Kind.OCCLUSION
                    ) {
                        return "each field"
                    }
                }
                val requires = c.requires
                if (requires != null) {
                    if (c.each != null || requires.isEmpty() || requires.size > 64) return "requires"
                    if (requires.any { g -> g.isEmpty() || g.any { it !in kinds } }) return "requires"
                }
            }
            return null
        }

    override fun problem(fields: Map<String, String>): String? {
        super.problem(fields)?.let { return it }
        val kinds = this.fields.associate { it.id.toString() to it.kind }
        for ((key, value) in fields) {
            if (value.isEmpty()) continue
            when (kinds.getValue(key)) {
                FieldDef.Kind.IMAGE, FieldDef.Kind.AUDIO -> if (!HASH.matches(value)) return "media"
                FieldDef.Kind.OCCLUSION -> if (Occlusion(value).masks == null) return "occlusion"
                FieldDef.Kind.TEXT -> Unit
            }
        }
        return null
    }

    override fun media(note: Note): Set<String> = fields.flatMap { f ->
        val value = note.fields[f.id.toString()].orEmpty()
        when (f.kind) {
            FieldDef.Kind.OCCLUSION -> listOf(Occlusion(value).image)
            FieldDef.Kind.TEXT -> emptyList()
            else -> listOf(value)
        }
    }.filter { HASH.matches(it) }.toSet()

    override fun cards(note: Note): List<Int> {
        val each = cards.singleOrNull()?.each
        if (each != null) {
            val value = note.fields[each.toString()].orEmpty()
            val kind = fields.firstOrNull { it.id == each }?.kind
            return if (kind == FieldDef.Kind.OCCLUSION) {
                Occlusion(value).masks?.map { it.group }?.distinct()?.sorted().orEmpty()
            } else {
                Markup(value).clozes.toList()
            }
        }
        return cards.filter { it.exists(note) }.map { it.id }
    }

    override fun side(note: Note, card: Int, back: Boolean): CardSide {
        // A template with `each` has one card definition, for all its cards (problem makes sure).
        val def = cards.firstOrNull { it.each != null || it.id == card } ?: return CardSide(emptyArray())
        val fields = fields.associateBy { it.id }
        val pieces = (if (back) def.back else def.front).mapNotNull { block ->
            when (block) {
                Block.Divider -> Piece("divider")

                is Block.Label -> Piece(
                    "label",
                    (block.size ?: Block.Size.NORMAL).value,
                    block.align != Block.Align.START,
                    text = block.text,
                )

                is Block.TypeIn -> Piece("typein", text = Markup(note.fields[block.id.toString()].orEmpty()).plain)

                is Block.Field -> {
                    val size = (block.size ?: Block.Size.NORMAL).value
                    val center = block.align != Block.Align.START
                    val field = fields[block.id] ?: return@mapNotNull null
                    val value = note.fields[field.id.toString()].orEmpty()
                    if (value.isBlank()) return@mapNotNull null
                    val active = if (def.each == field.id) card else 0
                    when (field.kind) {
                        FieldDef.Kind.TEXT -> Piece(
                            "text",
                            size,
                            center,
                            clozed(Markup(value).blocks, active, back).toTypedArray(),
                            speech = if (field.tts && (back || active == 0)) Markup(value).plain else null,
                            foreign = field.tts,
                        )

                        FieldDef.Kind.IMAGE -> Piece("image", size, center, media = value)

                        FieldDef.Kind.AUDIO -> Piece("audio", size, center, media = value)

                        FieldDef.Kind.OCCLUSION -> {
                            val occlusion = Occlusion(value)
                            val masks = occlusion.masks.orEmpty().map {
                                val isActive = it.group == active
                                Mask(it.group, it.x, it.y, it.w, it.h, active = isActive, hidden = !(back && isActive))
                            }
                            Piece("occlusion", size, center, media = occlusion.image, masks = masks.toTypedArray())
                        }
                    }
                }
            }
        }
        return CardSide(pieces.toTypedArray())
    }

    /** Hides cloze [active] on the front, marks it on the back, and shows other clozes as text. */
    private fun clozed(blocks: List<TextBlock>, active: Int, back: Boolean): List<TextBlock> = blocks.map { block ->
        block.map { runs ->
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
}

/** One of Anki's note types, kept as Anki's HTML and shown 1:1: its notes are read-only. */
data class AnkiTemplate(
    override val id: String,
    override val name: String,
    override val fields: List<FieldDef>,
    val cards: List<AnkiCardDef>,
    val css: String,
    override val v: Long = 0,
    override val ownerId: String? = null,
    override val deletedAt: Long? = null,
) : Template() {
    override fun with(id: String, v: Long, ownerId: String?, deletedAt: Long?) =
        copy(id = id, v = v, ownerId = ownerId, deletedAt = deletedAt)

    override val problem: String? get() = shapeProblem(cards.map { it.id })

    /** The field whose clozes make the cards, when this is Anki's cloze note type. */
    val clozeField: String? get() = cards.singleOrNull()?.front?.filtered("cloze")?.firstOrNull()

    /** A note's fields by name. */
    fun named(note: Note): Map<String, String> = fields.associate { it.name to note.fields[it.id.toString()].orEmpty() }

    override fun media(note: Note): Set<String> = note.fields.values.flatMap { v ->
        MEDIA.findAll(v).map { it.groupValues[1] }
    }.filter { HASH.matches(it) }.toSet()

    /** The cards [note] has, as Anki makes them: one per cloze, or each card whose front shows a filled field. */
    override fun cards(note: Note): List<Int> {
        val fields = named(note)
        val front = cards.singleOrNull()?.front
        if (front != null && clozeField != null) {
            return front.filtered("cloze").flatMap { Markup(fields[it].orEmpty()).clozes }.distinct().sorted()
        }
        return cards.filter { it.front.shows(fields) }.map { it.id }
    }

    override fun side(note: Note, card: Int, back: Boolean): CardSide {
        val fields = named(note)
        val def = cards.singleOrNull() ?: cards.firstOrNull { it.id == card } ?: return CardSide(emptyArray())
        val front = def.front.render(fields, card, false, "")
        val html = if (back) def.back.render(fields, card, true, front) else front
        return CardSide(emptyArray(), html, css)
    }

    private companion object {
        val MEDIA = Regex("rondo-media:([0-9a-f]{64})")
    }
}

/** A field of a note type. [tts]: in the deck's language, so read aloud and spelled as it is. */
data class FieldDef(val id: Int, val name: String, val kind: Kind, val tts: Boolean = false) {
    enum class Kind(val value: String) { TEXT("text"), IMAGE("image"), AUDIO("audio"), OCCLUSION("occlusion") }
}

/**
 * A card of a Rondo note type: its front and back. [each] makes one card per cloze number or mask
 * group of that field; [requires] says when a note has the card, as Anki does: when every field of
 * one group is filled. Absent, every field the front shows must be.
 */
data class CardDef(
    val id: Int,
    val name: String,
    val front: List<Block>,
    val back: List<Block>,
    val each: Int? = null,
    val requires: List<List<Int>>? = null,
) {
    /** Whether [note] has this card, by its fields (cards made by [each] are counted by the note type). */
    fun exists(note: Note): Boolean {
        fun filled(field: Int) = note.fields[field.toString()].orEmpty().isNotBlank()
        return requires?.any { group -> group.all(::filled) }
            ?: front.filterIsInstance<Block.Field>().map { it.id }.all(::filled)
    }
}

/** A card of an Anki note type: Anki's HTML for its front and back. */
data class AnkiCardDef(val id: Int, val name: String, val frontHtml: String, val backHtml: String) {
    val front: AnkiSide by lazy { AnkiSide(frontHtml) }
    val back: AnkiSide by lazy { AnkiSide(backHtml) }
}

/** What a card side shows, in order. */
sealed class Block {
    /** A field's content. */
    data class Field(val id: Int, val size: Size? = null, val align: Align? = null) : Block()

    /** A line across the card. */
    data object Divider : Block()

    /** Text of its own, the same on every note. */
    data class Label(val text: String, val size: Size? = null, val align: Align? = null) : Block()

    /** A box to type a field's text into, then compared with it. */
    data class TypeIn(val id: Int) : Block()

    enum class Size(val value: String) { SMALL("small"), NORMAL("normal"), LARGE("large") }

    enum class Align(val value: String) { START("start"), CENTER("center") }
}
