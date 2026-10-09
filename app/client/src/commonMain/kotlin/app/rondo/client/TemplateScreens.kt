package app.rondo.client

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.rondo.core.CardSide
import app.rondo.core.Ids
import app.rondo.core.Templates
import app.rondo.core.model.Block
import app.rondo.core.model.CardDef
import app.rondo.core.model.FieldDef
import app.rondo.core.model.Note
import app.rondo.core.model.Template
import app.rondo.core.model.TemplateDef
import app.rondo.core.nowMillis
import kotlin.js.JsExport

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
        val store = app.rondo.store
        val use = store.q.templateUse().awaitAsList().associate { it.template_id to it.notes.toInt() }
        val shown = store.templates().values.filter {
            it.deletedAt == null && (Templates.isBuiltin(it.id) || it.ownerId == store.me)
        }
        val items = shown.sortedWith(compareBy({ !Templates.isBuiltin(it.id) }, { it.name.lowercase() })).map {
            val notes = use[it.id] ?: 0
            val builtin = Templates.isBuiltin(it.id)
            val sample = samples(it, sampleNote(it)).firstOrNull()?.front
            val summary = if (builtin) strings.summary(it.id) else strings.inUse(notes)
            TemplateItem(it.id, it.name, builtin, it.kind == Templates.ANKI, notes, summary, sample)
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
    private val store get() = app.rondo.store
    private lateinit var draft: Template
    private var stored: Template? = null
    private var saved = false

    override suspend fun load(): TemplateState {
        if (!::draft.isInitialized) {
            stored = id?.let { store.template(it) }
            val base = from?.let { store.template(it) }
            draft = stored ?: base?.copy(
                id = Ids.new(),
                name = strings.copyOf(base.name),
                kind = Templates.RONDO,
                ownerId = store.me,
                v = 0,
            ) ?: newTemplate()
        }
        val d = draft.def
        val builtin = Templates.isBuiltin(draft.id)
        val sample = sampleNote(draft)
        val first = runCatching { Templates.cards(draft, sample).firstOrNull() }.getOrNull()
        val use = stored?.let { t -> store.q.templateUse().awaitAsList().firstOrNull { it.template_id == t.id } }
        fun blocks(list: List<Block>) = list.map { b ->
            val kind = when {
                b.divider == true -> "divider"
                b.label != null -> "label"
                b.typein != null -> "typein"
                else -> "field"
            }
            BlockItem(
                kind,
                b.field ?: b.typein ?: 0,
                b.propertySize?.value ?: "normal",
                b.align != Block.Align.START,
                b.label.orEmpty(),
            )
        }.toTypedArray()
        val fields = d.fields.map { FieldItem(it.id, it.name, it.kind.value, it.tts == true) }
        val cards = d.cards.map { CardItem(it.id, it.name, blocks(it.front), blocks(it.back)) }
        val each = d.cards.firstNotNullOfOrNull { it.each } ?: 0
        val problem = Templates.problem(draft)?.let(strings::problem)
        fun side(card: Int, back: Boolean) = Templates.layout(draft, sample, card, back)
        val preview = first?.let { listOf(side(it, false), side(it, true)) }.orEmpty()
        val q = Questions(d)
        fun items(ids: List<Int>) = ids.mapNotNull { id -> fields.firstOrNull { it.id == id } }.toTypedArray()
        return TemplateState(
            draft.id, draft.name, fields.toTypedArray(), cards.toTypedArray(), each, builtin,
            builtin || draft.kind == Templates.ANKI, use?.notes?.toInt() ?: 0, problem, preview.toTypedArray(), saved,
            items(q.front), items(q.back), q.reverse?.let { q.reverseCard != null },
            q.answer?.let { q.typeIn }, q.audio?.let { q.listenCard != null },
        )
    }

    /** A new note type to start from: a front and a back, one card. */
    private fun newTemplate(): Template {
        val front = FieldDef(1, strings.front, FieldDef.Kind.TEXT)
        val fields = listOf(front, FieldDef(2, strings.backSide, FieldDef.Kind.TEXT))
        val back = listOf(Block(field = 1), Block(divider = true), Block(field = 2))
        val card = CardDef(1, "${strings.cardsTitle} 1", listOf(Block(field = 1)), back)
        return Template(Ids.new(), strings.newTemplate, Templates.RONDO, TemplateDef(fields, listOf(card)), 0, store.me)
    }

    private fun change(block: (TemplateDef) -> TemplateDef) = act {
        if (Templates.isBuiltin(draft.id) || draft.kind == Templates.ANKI) return@act
        draft = draft.copy(def = block(draft.def))
        show(load())
    }

    /** Adds a field of [kind] where the first card shows it: asked on the front, or answered on the back. */
    fun addFieldTo(kind: String, front: Boolean) = change { d ->
        val id = (d.fields.maxOfOrNull { it.id } ?: 0) + 1
        val field = FieldDef(id, "${strings.field} $id", FieldDef.Kind.entries.first { it.value == kind })
        val first = d.cards.firstOrNull() ?: return@change d.copy(fields = d.fields + field)
        val shown = Block(field = id)
        val card = if (front) {
            // The back repeats the front above its divider: the new field goes there too.
            val at = first.back.indexOfFirst { it.divider == true }.takeIf { it >= 0 } ?: first.back.size
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
            val at = indexOfFirst { it.field == id }
            if (at < 0) return this
            val fieldsAt = indices.filter { this[it].field != null }
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
        val front = listOf(Block(field = answer, propertySize = LARGE))
        val back = front + listOf(Block(divider = true), Block(field = question))
        val card = CardDef(nextCard(d), strings.reverseCard, front, back)
        d.copy(cards = d.cards.filter { it != q.reverseCard } + listOfNotNull(card.takeIf { on }))
    }

    /** Asks to type the answer before showing it, on the first card. */
    fun setTypeAnswer(on: Boolean) = change { d ->
        val answer = Questions(d).answer ?: return@change d
        val first = d.cards.first()
        val front = first.front.filter { it.typein == null }
        val back = first.back.filter { it.typein == null }
        val at = back.indexOfFirst { it.divider == true }.takeIf { it >= 0 } ?: back.size
        val card = if (on) {
            val typed = Block(typein = answer)
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
        val back = listOf(Block(field = audio), Block(divider = true)) + q.front.map { Block(field = it) }
        val card = CardDef(nextCard(d), strings.listeningCard, listOf(Block(field = audio)), back)
        d.copy(cards = d.cards.filter { it != q.listenCard } + listOfNotNull(card.takeIf { on }))
    }

    private fun nextCard(d: TemplateDef) = (d.cards.maxOfOrNull { it.id } ?: 0) + 1

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
        draft = draft.copy(name = name)
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
        d.copy(fields = d.fields.map { if (it.id == id) it.copy(tts = on.takeIf { it }) else it })
    }

    fun moveField(id: Int, delta: Int) = change { d ->
        d.copy(fields = d.fields.moved(d.fields.indexOfFirst { it.id == id }, delta))
    }

    /** Removes a field and every block showing it. */
    fun removeField(id: Int) = change { d ->
        val keep = { b: Block -> b.field != id && b.typein != id }
        d.copy(
            fields = d.fields.filter { it.id != id },
            cards = d.cards.map { c ->
                c.copy(
                    front = c.front.filter(keep),
                    back = c.back.filter(keep),
                    each = c.each?.takeIf { it != id },
                )
            },
        )
    }

    fun addCard() = change { d ->
        val id = (d.cards.maxOfOrNull { it.id } ?: 0) + 1
        val f = d.fields.map { it.id }
        val back = listOf(Block(field = f.last()), Block(divider = true), Block(field = f.first()))
        d.copy(cards = d.cards + CardDef(id, "${strings.cardsTitle} $id", listOf(Block(field = f.last())), back))
    }

    fun renameCard(id: Int, name: String) = change { d ->
        d.copy(cards = d.cards.map { if (it.id == id) it.copy(name = name) else it })
    }

    fun removeCard(id: Int) = change { d -> d.copy(cards = d.cards.filter { it.id != id }) }

    /** One card per cloze number or mask group of [fieldId]; 0 turns it off. */
    fun setEach(fieldId: Int) =
        change { d -> d.copy(cards = d.cards.map { it.copy(each = fieldId.takeIf { f -> f > 0 }) }) }

    fun addBlock(card: Int, back: Boolean, kind: String, field: Int) = cards(card, back) {
        it += when (kind) {
            "divider" -> Block(divider = true)
            "label" -> Block(label = strings.label)
            "typein" -> Block(typein = field)
            else -> Block(field = field)
        }
    }

    /** A block's options: [size] small, normal or large; [text] for a label. */
    fun setBlock(card: Int, back: Boolean, index: Int, size: String, center: Boolean, text: String) =
        cards(card, back) { list ->
            val b = list.getOrNull(index) ?: return@cards
            val sized = Block.PropertySize.entries.firstOrNull { it.value == size }
            list[index] = b.copy(
                propertySize = sized?.takeIf { it != Block.PropertySize.NORMAL },
                align = if (center) null else Block.Align.START,
                label = if (b.label != null) text else null,
            )
        }

    fun removeBlock(card: Int, back: Boolean, index: Int) = cards(card, back) {
        if (index in it.indices) it.removeAt(index)
    }

    fun moveBlock(card: Int, back: Boolean, index: Int, delta: Int) = cards(card, back) { list ->
        val moved = list.moved(index, delta)
        list.clear()
        list += moved
    }

    /** A built-in type as your own, to change. */
    fun duplicate() = act {
        draft = draft.copy(id = Ids.new(), name = "${draft.name} 2", kind = Templates.RONDO, ownerId = store.me)
        stored = null
        show(load())
    }

    fun save() = act {
        draft = app.rondo.library.saveTemplate(draft)
        stored = draft
        saved = true
        show(load())
        app.rondo.syncSoon()
    }

    fun delete() = act {
        app.rondo.library.saveTemplate(draft.copy(deletedAt = nowMillis()))
        saved = true
        show(load())
    }
}

private val LARGE = Block.PropertySize.LARGE

/**
 * A note type's first card read as questions: the fields it asks ([front]) and answers ([back]),
 * and the cards that ask the other way round or by sound, when the type can.
 */
private class Questions(d: TemplateDef) {
    private val first = d.cards.firstOrNull()
    private val kinds = d.fields.associate { it.id to it.kind }
    private val simple = first != null && first.each == null
    val front: List<Int> = first?.front.orEmpty().mapNotNull { it.field }.distinct()
    val back: List<Int> = first?.back.orEmpty().mapNotNull { it.field }.filter { it !in front }.distinct()

    /** The first answer that's text: what typing and the other way round use. */
    val answer: Int? = back.firstOrNull { kinds[it] == FieldDef.Kind.TEXT }?.takeIf { simple }
    val reverse: Int? = answer?.takeIf { front.isNotEmpty() }
    private val sound = d.fields.firstOrNull { it.kind == FieldDef.Kind.AUDIO }?.id
    val audio: Int? = sound?.takeIf { simple && front.isNotEmpty() }
    val typeIn: Boolean = first?.front.orEmpty().any { it.typein != null }
    private val others = d.cards.drop(1)
    val reverseCard: CardDef? = others.firstOrNull { reverse != null && it.front.firstOrNull()?.field == reverse }
    val listenCard: CardDef? = others.firstOrNull { audio != null && it.front.singleOrNull()?.field == audio }
}

private fun <T> List<T>.moved(index: Int, delta: Int): List<T> {
    val to = index + delta
    if (index !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(index)) }
}
