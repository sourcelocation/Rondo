package app.rondo.client

import app.rondo.core.AnkiTemplate
import app.rondo.core.Block
import app.rondo.core.CardDef
import app.rondo.core.CardSide
import app.rondo.core.FieldDef
import app.rondo.core.RondoTemplate
import app.rondo.core.Template
import kotlin.js.JsExport
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** A note type in the picker: its name, what it's for, and its first card's front for a sample note. */
@JsExport
class TemplateItem internal constructor(
    val id: String,
    val name: String,
    val builtin: Boolean,
    val anki: Boolean,
    val notes: Int,
    val summary: String,
    val sample: CardSide?,
)

@JsExport
class TemplatesState internal constructor(val items: Array<TemplateItem>)

/** Note types: the built-in ones, then yours, with how many notes use each. */
@JsExport
class TemplatesScreen internal constructor(override val app: App) : Screen<TemplatesState>() {
    override suspend fun load(): TemplatesState {
        val library = app.rondo.library
        val use = library.templateUse()
        val shown = library.templates().values.filter {
            it.deletedAt == null && (it.builtin || it.ownerId == library.me)
        }
        val items = shown.sortedWith(compareBy({ !it.builtin }, { it.name.lowercase() })).map {
            val notes = use[it.id] ?: 0
            val sample = samples(it, sampleNote(it)).firstOrNull()?.front
            val summary = if (it.builtin) strings.summary(it.id) else strings.inUse(notes)
            TemplateItem(it.id, it.name, it.builtin, it is AnkiTemplate, notes, summary, sample)
        }
        return TemplatesState(items.toTypedArray())
    }
}

@JsExport
class FieldItem internal constructor(val id: Int, val name: String, val kind: String, val tts: Boolean)

/** A block on a card side. [kind]: field, divider, label or typein. */
@JsExport
class BlockItem internal constructor(
    val kind: String,
    val field: Int,
    val size: String,
    val center: Boolean,
    val text: String,
)

@JsExport
class CardItem internal constructor(
    val id: Int,
    val name: String,
    val front: Array<BlockItem>,
    val back: Array<BlockItem>,
)

@JsExport
class TemplateState internal constructor(
    val id: String,
    val name: String,
    val fields: Array<FieldItem>,
    val cards: Array<CardItem>,
    /** The field making one card per cloze or mask group, or 0. */
    val each: Int,
    val builtin: Boolean,
    val readOnly: Boolean,
    val inUse: Int,
    val problem: String?,
    /** The first card's front and back for a sample note. */
    val preview: Array<CardSide>,
    val saved: Boolean,
    /** The fields as the first card places them, to edit on the card: asked on the front, answered on the back. */
    val front: Array<FieldItem>,
    val back: Array<FieldItem>,
    /** The questions it asks besides the first card; null when it can't ask that one. */
    val bothWays: Boolean?,
    val typeAnswer: Boolean?,
    val listening: Boolean?,
)

/**
 * Building a note type: on the card (fields on the front and back of its first card, and the
 * questions it asks), or block by block for every card. Built-in types are copied to edit; [from]
 * starts a new type as a copy of another.
 */
@JsExport
class TemplateScreen internal constructor(override val app: App, private val id: String?, private val from: String?) :
    Screen<TemplateState>() {
    override val live get() = false
    private val library get() = app.rondo.library
    private lateinit var draft: Template
    private var stored: Template? = null
    private var saved = false

    override suspend fun load(): TemplateState {
        if (!::draft.isInitialized) {
            stored = id?.let { library.template(it) }
            val base = from?.let { library.template(it) } as? RondoTemplate
            draft = stored ?: base?.copy(id = newId(), name = strings.copyOf(base.name), ownerId = library.me, v = 0)
                ?: newTemplate()
        }
        val cards = (draft as? RondoTemplate)?.cards.orEmpty()
        val sample = sampleNote(draft)
        val first = runCatching { draft.cards(sample).firstOrNull() }.getOrNull()
        val use = stored?.let { library.templateUse()[it.id] } ?: 0
        fun blocks(list: List<Block>) = list.map { b ->
            when (b) {
                Block.Divider -> BlockItem("divider", 0, "normal", true, "")

                is Block.Label -> BlockItem(
                    "label",
                    0,
                    (b.size ?: Block.Size.NORMAL).value,
                    b.align != Block.Align.START,
                    b.text,
                )

                is Block.TypeIn -> BlockItem("typein", b.id, "normal", true, "")

                is Block.Field -> BlockItem(
                    "field",
                    b.id,
                    (b.size ?: Block.Size.NORMAL).value,
                    b.align != Block.Align.START,
                    "",
                )
            }
        }.toTypedArray()
        val fields = draft.fields.map { FieldItem(it.id, it.name, it.kind.value, it.tts) }
        val cardItems = cards.map { CardItem(it.id, it.name, blocks(it.front), blocks(it.back)) }
        val each = cards.firstNotNullOfOrNull { it.each } ?: 0
        val problem = draft.problem?.let(strings::problem)
        fun side(card: Int, back: Boolean) = draft.side(sample, card, back)
        val preview = first?.let { listOf(side(it, false), side(it, true)) }.orEmpty()
        val q = (draft as? RondoTemplate)?.let(::Questions)
        fun items(ids: List<Int>) = ids.mapNotNull { id -> fields.firstOrNull { it.id == id } }.toTypedArray()
        return TemplateState(
            draft.id, draft.name, fields.toTypedArray(), cardItems.toTypedArray(), each, draft.builtin,
            draft.builtin || draft is AnkiTemplate, use, problem, preview.toTypedArray(), saved,
            items(q?.front.orEmpty()), items(q?.back.orEmpty()), q?.reverse?.let { q.reverseCard != null },
            q?.answer?.let { q.typeIn }, q?.audio?.let { q.listenCard != null },
        )
    }

    private fun newId() = Uuid.generateV7().toString()

    /** A new note type to start from: a front and a back, one card. */
    private fun newTemplate(): RondoTemplate {
        val front = FieldDef(1, strings.front, FieldDef.Kind.TEXT)
        val fields = listOf(front, FieldDef(2, strings.backSide, FieldDef.Kind.TEXT))
        val back = listOf(Block.Field(1), Block.Divider, Block.Field(2))
        val card = CardDef(1, "${strings.cardsTitle} 1", listOf(Block.Field(1)), back)
        return RondoTemplate(newId(), strings.newTemplate, fields, listOf(card), 0, library.me)
    }

    private fun change(block: (RondoTemplate) -> RondoTemplate) = act {
        val d = draft as? RondoTemplate ?: return@act
        if (d.builtin) return@act
        draft = block(d)
        show(load())
    }

    /** Adds a field of [kind] where the first card shows it: asked on the front, or answered on the back. */
    fun addFieldTo(kind: String, front: Boolean) = change { d ->
        val id = (d.fields.maxOfOrNull { it.id } ?: 0) + 1
        val field = FieldDef(id, "${strings.field} $id", FieldDef.Kind.entries.first { it.value == kind })
        val first = d.cards.firstOrNull() ?: return@change d.copy(fields = d.fields + field)
        val shown = Block.Field(id)
        val card = if (front) {
            // The back repeats the front above its divider: the new field goes there too.
            val at = first.back.indexOf(Block.Divider).takeIf { it >= 0 } ?: first.back.size
            first.copy(front = first.front + shown, back = first.back.toMutableList().apply { add(at, shown) })
        } else {
            first.copy(back = first.back + shown)
        }
        d.copy(fields = d.fields + field, cards = listOf(card) + d.cards.drop(1))
    }

    /** Moves a field up or down among the ones on its side of the first card. */
    fun moveOnCard(id: Int, delta: Int) = change { d ->
        val first = d.cards.firstOrNull() ?: return@change d
        fun List<Block>.shift(): List<Block> {
            val at = indexOfFirst { it is Block.Field && it.id == id }
            if (at < 0) return this
            val fieldsAt = indices.filter { this[it] is Block.Field }
            val to = fieldsAt.getOrNull(fieldsAt.indexOf(at) + delta) ?: return this
            return toMutableList().apply {
                set(at, this@shift[to])
                set(to, this@shift[at])
            }
        }
        d.copy(cards = listOf(first.copy(front = first.front.shift(), back = first.back.shift())) + d.cards.drop(1))
    }

    /** Also asks the other way round: the answer on the front, the question to recall. */
    fun setBothWays(on: Boolean) = change { d ->
        val q = Questions(d)
        val question = q.front.firstOrNull() ?: return@change d
        val answer = q.reverse ?: return@change d
        val front = listOf(Block.Field(answer, Block.Size.LARGE))
        val back = front + listOf(Block.Divider, Block.Field(question))
        val card = CardDef(nextCard(d), strings.reverseCard, front, back)
        d.copy(cards = d.cards.filter { it != q.reverseCard } + listOfNotNull(card.takeIf { on }))
    }

    /** Asks to type the answer before showing it, on the first card. */
    fun setTypeAnswer(on: Boolean) = change { d ->
        val answer = Questions(d).answer ?: return@change d
        val first = d.cards.first()
        val front = first.front.filter { it !is Block.TypeIn }
        val back = first.back.filter { it !is Block.TypeIn }
        val at = back.indexOf(Block.Divider).takeIf { it >= 0 } ?: back.size
        val card = if (on) {
            val typed = Block.TypeIn(answer)
            first.copy(front = front + typed, back = back.toMutableList().apply { add(at, typed) })
        } else {
            first.copy(front = front, back = back)
        }
        d.copy(cards = listOf(card) + d.cards.drop(1))
    }

    /** Also plays the sound on its own and asks what it says. */
    fun setListening(on: Boolean) = change { d ->
        val q = Questions(d)
        val audio = q.audio ?: return@change d
        val back = listOf(Block.Field(audio), Block.Divider) + q.front.map { Block.Field(it) }
        val card = CardDef(nextCard(d), strings.listeningCard, listOf(Block.Field(audio)), back)
        d.copy(cards = d.cards.filter { it != q.listenCard } + listOfNotNull(card.takeIf { on }))
    }

    private fun nextCard(d: RondoTemplate) = (d.cards.maxOfOrNull { it.id } ?: 0) + 1

    private fun cards(card: Int, back: Boolean, block: (MutableList<Block>) -> Unit) = change { d ->
        d.copy(
            cards = d.cards.map { c ->
                if (c.id != card) return@map c
                val blocks = (if (back) c.back else c.front).toMutableList().also(block)
                if (back) c.copy(back = blocks) else c.copy(front = blocks)
            },
        )
    }

    fun rename(name: String) = act {
        draft = when (val d = draft) {
            is RondoTemplate -> d.copy(name = name)
            is AnkiTemplate -> d.copy(name = name)
        }
        show(load())
    }

    fun addField(kind: String) = change { d ->
        val id = (d.fields.maxOfOrNull { it.id } ?: 0) + 1
        val field = FieldDef(id, "${strings.field} $id", FieldDef.Kind.entries.first { it.value == kind })
        d.copy(fields = d.fields + field)
    }

    fun renameField(id: Int, name: String) = change { d ->
        d.copy(fields = d.fields.map { if (it.id == id) it.copy(name = name) else it })
    }

    fun setTts(id: Int, on: Boolean) = change { d ->
        d.copy(fields = d.fields.map { if (it.id == id) it.copy(tts = on) else it })
    }

    fun moveField(id: Int, delta: Int) = change { d ->
        d.copy(fields = moved(d.fields, d.fields.indexOfFirst { it.id == id }, delta))
    }

    /** Removes a field and every block showing it; a card that needed it no longer does. */
    fun removeField(id: Int) = change { d ->
        val keep = { b: Block -> !(b is Block.Field && b.id == id) && !(b is Block.TypeIn && b.id == id) }
        d.copy(
            fields = d.fields.filter { it.id != id },
            cards = d.cards.map { c ->
                c.copy(
                    front = c.front.filter(keep),
                    back = c.back.filter(keep),
                    each = c.each?.takeIf { it != id },
                    requires = c.requires?.map { g -> g - id }?.filter { it.isNotEmpty() }?.ifEmpty { null },
                )
            },
        )
    }

    fun addCard() = change { d ->
        val id = (d.cards.maxOfOrNull { it.id } ?: 0) + 1
        val f = d.fields.map { it.id }
        val back = listOf(Block.Field(f.last()), Block.Divider, Block.Field(f.first()))
        d.copy(cards = d.cards + CardDef(id, "${strings.cardsTitle} $id", listOf(Block.Field(f.last())), back))
    }

    fun renameCard(id: Int, name: String) = change { d ->
        d.copy(cards = d.cards.map { if (it.id == id) it.copy(name = name) else it })
    }

    fun removeCard(id: Int) = change { d -> d.copy(cards = d.cards.filter { it.id != id }) }

    /** One card per cloze number or mask group of [fieldId]; 0 turns it off. */
    fun setEach(fieldId: Int) = change { d ->
        val each = fieldId.takeIf { it > 0 }
        // Cards made one per cloze or mask exist by those, not by fields filled.
        d.copy(cards = d.cards.map { it.copy(each = each, requires = it.requires.takeIf { each == null }) })
    }

    fun addBlock(card: Int, back: Boolean, kind: String, field: Int) = cards(card, back) {
        it += when (kind) {
            "divider" -> Block.Divider
            "label" -> Block.Label(strings.label)
            "typein" -> Block.TypeIn(field)
            else -> Block.Field(field)
        }
    }

    /** A block's options: [size] small, normal or large; [text] for a label. */
    fun setBlock(card: Int, back: Boolean, index: Int, size: String, center: Boolean, text: String) =
        cards(card, back) { list ->
            val b = list.getOrNull(index) ?: return@cards
            val sized = Block.Size.entries.firstOrNull { it.value == size }?.takeIf { it != Block.Size.NORMAL }
            val align = if (center) null else Block.Align.START
            list[index] = when (b) {
                is Block.Field -> b.copy(size = sized, align = align)
                is Block.Label -> b.copy(text = text, size = sized, align = align)
                else -> b
            }
        }

    fun removeBlock(card: Int, back: Boolean, index: Int) = cards(card, back) {
        if (index in it.indices) it.removeAt(index)
    }

    fun moveBlock(card: Int, back: Boolean, index: Int, delta: Int) = cards(card, back) { list ->
        val moved = moved(list.toList(), index, delta)
        list.clear()
        list += moved
    }

    /** A built-in type as your own, to change. */
    fun duplicate() = act {
        val d = draft as? RondoTemplate ?: return@act
        draft = d.copy(id = newId(), name = "${d.name} 2", ownerId = library.me)
        stored = null
        show(load())
    }

    fun save() = act {
        draft = library.saveTemplate(draft)
        stored = draft
        saved = true
        show(load())
        app.rondo.syncSoon()
    }

    fun delete() = act {
        library.saveTemplate(draft.with(deletedAt = Clock.System.now().toEpochMilliseconds()))
        saved = true
        show(load())
    }

    /** [list] with the item at [index] moved by [delta], when it can be. */
    private fun <T> moved(list: List<T>, index: Int, delta: Int): List<T> {
        val to = index + delta
        if (index !in list.indices || to !in list.indices) return list
        return list.toMutableList().apply { add(to, removeAt(index)) }
    }
}

/**
 * A note type's first card read as questions: the fields it asks ([front]) and answers ([back]),
 * and the cards that ask the other way round or by sound, when the type can.
 */
private class Questions(d: RondoTemplate) {
    private val first = d.cards.firstOrNull()
    private val kinds = d.fields.associate { it.id to it.kind }
    private val simple = first != null && first.each == null
    val front: List<Int> = first?.front.orEmpty().filterIsInstance<Block.Field>().map { it.id }.distinct()
    val back: List<Int> = first?.back.orEmpty().filterIsInstance<Block.Field>().map {
        it.id
    }.filter { it !in front }.distinct()

    /** The first answer that's text: what typing and the other way round use. */
    val answer: Int? = back.firstOrNull { kinds[it] == FieldDef.Kind.TEXT }?.takeIf { simple }
    val reverse: Int? = answer?.takeIf { front.isNotEmpty() }
    private val sound = d.fields.firstOrNull { it.kind == FieldDef.Kind.AUDIO }?.id
    val audio: Int? = sound?.takeIf { simple && front.isNotEmpty() }
    val typeIn: Boolean = first?.front.orEmpty().any { it is Block.TypeIn }
    private val others = d.cards.drop(1)
    val reverseCard: CardDef? = others.firstOrNull {
        reverse != null &&
            (it.front.firstOrNull() as? Block.Field)?.id == reverse
    }
    val listenCard: CardDef? = others.firstOrNull {
        audio != null &&
            (it.front.singleOrNull() as? Block.Field)?.id == audio
    }
}
