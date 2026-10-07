package app.rondo.client

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.rondo.core.CardSide
import app.rondo.core.Ids
import app.rondo.core.Occlusion
import app.rondo.core.Templates
import app.rondo.core.Tree
import app.rondo.core.model.Block
import app.rondo.core.model.CardDef
import app.rondo.core.model.Deck
import app.rondo.core.model.FieldDef
import app.rondo.core.model.Note
import app.rondo.core.model.Template
import app.rondo.core.model.TemplateDef
import app.rondo.core.nowMillis
import kotlin.js.JsExport

@JsExport
class Choice internal constructor(val id: String, val label: String)

/** [decks] as pickers list them: by path, alphabetically. */
internal fun Tree.choices(decks: Collection<Deck>): List<Choice> =
    decks.map { Choice(it.id, path(it.id).joinToString(" › ")) }.sortedBy { it.label.lowercase() }

/** An occlusion mask, in fractions of the image. */
@JsExport
class Box internal constructor(val group: Int, val x: Double, val y: Double, val w: Double, val h: Double)

/**
 * One field as the editor shows it: markup for text, a media hash, or an image with masks.
 * [each]: its clozes make the note's cards. [side]: front or back, where the first card shows it;
 * [size] as it shows there. [foreign]: in the deck's language.
 */
@JsExport
class FieldValue internal constructor(
    val id: String,
    val name: String,
    val kind: String,
    val value: String,
    val image: String?,
    val masks: Array<Box>,
    val each: Boolean,
    val side: String,
    val size: String,
    val foreign: Boolean,
)

@JsExport
class EditorState internal constructor(
    val noteId: String?,
    val deckId: String?,
    val deckName: String?,
    /** The deck's language (BCP 47), for fields in it. */
    val language: String?,
    val templateId: String,
    val templateName: String,
    val fields: Array<FieldValue>,
    val decks: Array<Choice>,
    val templates: Array<Choice>,
    val readOnly: Boolean,
    val anki: Boolean,
    /** The cards the note makes, as they'll show. */
    val cards: Array<Sample>,
    val canSave: Boolean,
    /** Why it can't be added yet. */
    val problem: String?,
    /** Notes added since the editor opened. */
    val added: Int,
    /** The note just added can be taken back. */
    val canUndoAdd: Boolean,
    val gone: Boolean,
)

/** A note showing each text field's name (a cloze where the field makes the cards): what previews show. */
internal fun sampleNote(t: Template): Note = Note(
    "",
    "",
    t.id,
    t.def.fields.filter { it.kind == FieldDef.Kind.TEXT }.associate { f ->
        f.id.toString() to if (t.def.cards.any { it.each == f.id }) "{{c1::${f.name}}}" else f.name
    },
    0,
)

/** Every card [note] makes under [t], front and back. */
internal fun samples(t: Template, note: Note): Array<Sample> = runCatching {
    Templates.cards(t, note).map { Sample(Templates.layout(t, note, it, false), Templates.layout(t, note, it, true)) }
}.getOrDefault(emptyList()).toTypedArray()

/** Adding notes one after another (noteId null), or editing one: fields, type and deck. */
@JsExport
class EditorScreen internal constructor(
    override val app: App,
    private var noteId: String?,
    private var deckId: String?,
) : Screen<EditorState>() {
    override val live get() = false
    private val store get() = app.rondo.store
    private val library get() = app.rondo.library
    private var note: Note? = null
    private var templateId = Templates.BASIC
    private var values = HashMap<String, String>()
    private var added = 0
    private var last: Note? = null
    private var ready = false

    override suspend fun load(): EditorState {
        if (!ready) {
            ready = true
            note = noteId?.let { store.note(it) }
            note?.let {
                deckId = it.deckId
                templateId = it.templateId
                values = HashMap(it.fields)
            } ?: run {
                templateId = store.meta("last_template")?.takeIf { store.template(it) != null } ?: Templates.BASIC
                deckId = deckId ?: store.meta("last_deck")
            }
        }
        val tree = store.tree()
        val access = library.access()
        val writable = tree.byId.values.filter { !tree.dead(it.id) && access.canWrite(it.id) == null }
        if (deckId == null || writable.none { it.id == deckId }) {
            deckId = if (note == null) writable.minByOrNull { tree.path(it.id).size }?.id else deckId
        }
        val template = store.template(templateId)
        val gone = (noteId != null && (note == null || note?.deletedAt != null)) || template == null
        val draft = Note(noteId ?: "", deckId ?: "", templateId, values.filterValues { it.isNotBlank() }, 0)
        val readOnly = template?.kind == Templates.ANKI || (note != null && access.canWrite(note!!.deckId) != null)
        val choices = store.templates().values.filter {
            it.deletedAt == null && it.kind == Templates.RONDO &&
                (Templates.isBuiltin(it.id) || it.ownerId == store.me)
        }
        val cards = template?.let { samples(it, draft) } ?: emptyArray()
        val problem = when {
            template == null || readOnly -> null
            deckId == null -> strings.makeDeckFirst
            Templates.fieldsProblem(template, draft.fields) != null -> strings.error("invalid")
            cards.isEmpty() -> missing(template)
            else -> null
        }
        return EditorState(
            noteId, deckId, tree[deckId]?.name, deckId?.let { tree.language(it) }, templateId,
            template?.name.orEmpty(), template?.let { fields(it) } ?: emptyArray(),
            tree.choices(writable).toTypedArray(),
            choices.sortedWith(compareBy({ !Templates.isBuiltin(it.id) }, { it.name.lowercase() }))
                .map { Choice(it.id, it.name) }.toTypedArray(),
            readOnly, template?.kind == Templates.ANKI, cards, !readOnly && !gone && problem == null,
            problem, added, last != null, gone,
        )
    }

    private fun fields(t: Template): Array<FieldValue> {
        val first = t.def.cards.firstOrNull()
        return t.def.fields.map { f ->
            val v = values[f.id.toString()].orEmpty()
            val occlusion = f.kind == FieldDef.Kind.OCCLUSION
            val rects = if (occlusion) Occlusion.parse(v).orEmpty() else emptyList()
            val masks = rects.map { Box(it.group, it.x, it.y, it.w, it.h) }
            val front = first?.front.orEmpty().firstOrNull { it.field == f.id }
            val block = front ?: first?.back.orEmpty().firstOrNull { it.field == f.id }
            FieldValue(
                f.id.toString(), f.name, f.kind.value, v, if (occlusion && v.isNotBlank()) Occlusion.image(v) else null,
                masks.toTypedArray(), t.def.cards.any { it.each == f.id }, if (front != null) "front" else "back",
                block?.propertySize?.value ?: "normal", f.tts == true,
            )
        }.toTypedArray()
    }

    /** What a note of [t] still needs before it makes a card. */
    private fun missing(t: Template): String {
        val each = t.def.cards.singleOrNull()?.each?.let { id -> t.def.fields.firstOrNull { it.id == id } }
        if (each?.kind == FieldDef.Kind.OCCLUSION) {
            return if (values[each.id.toString()].isNullOrBlank()) strings.addPicture else strings.drawMasks
        }
        if (each != null) return strings.makeBlank
        val front = t.def.cards.firstOrNull()?.front.orEmpty().firstNotNullOfOrNull { b ->
            t.def.fields.firstOrNull { it.id == b.field }
        }
        return strings.fillIn(front?.name ?: strings.front)
    }

    private fun edit(block: suspend () -> Unit) = act {
        block()
        show(load())
    }

    fun setDeck(id: String) = edit { deckId = id }

    /** Switches the note type, keeping what fits: fields in the same place and of the same kind. */
    fun setTemplate(id: String) = edit {
        val from = store.template(templateId)?.def?.fields.orEmpty()
        val to = store.template(id)?.def?.fields ?: return@edit
        values = HashMap(
            to.withIndex().mapNotNull { (i, f) ->
                val kept = from.getOrNull(i)?.takeIf { it.kind == f.kind }?.let { values[it.id.toString()] }
                kept?.let { f.id.toString() to it }
            }.toMap(),
        )
        templateId = id
    }

    /** A text field's markup, or a media field's hash. */
    fun set(fieldId: String, value: String) = edit { values[fieldId] = value }

    /** Stores a file (picked, dropped, pasted or recorded) in the field; an occlusion image starts without masks. */
    fun attach(fieldId: String, bytes: ByteArray, mime: String) = edit {
        val hash = app.rondo.media.add(bytes, mime)
        val kind = store.template(templateId)?.def?.fields?.firstOrNull { it.id.toString() == fieldId }?.kind
        values[fieldId] = if (kind == FieldDef.Kind.OCCLUSION) Occlusion.write(hash, emptyList()) else hash
    }

    /** Empties a field: removes its picture or sound. */
    fun clear(fieldId: String) = edit { values.remove(fieldId) }

    /** An occlusion field's masks, five numbers each: group, x, y, width, height (fractions). */
    fun setMasks(fieldId: String, masks: Array<Double>) = edit {
        val image = Occlusion.image(values[fieldId].orEmpty()).takeIf { Templates.HASH.matches(it) } ?: return@edit
        values[fieldId] = Occlusion.write(
            image,
            masks.toList().chunked(5).filter { it.size == 5 }
                .map { (group, x, y, w, h) -> Occlusion.Rect(group.toInt(), x, y, w, h) },
        )
    }

    /** Adds the note (and clears the fields for the next) or saves the one being edited; then [then]. */
    fun save(then: () -> Unit) = edit {
        val template = store.template(templateId) ?: return@edit
        val known = template.def.fields.map { it.id.toString() }.toSet()
        val fields = values.filter { (k, v) -> k in known && v.isNotBlank() }
        val deck = deckId ?: return@edit
        val stored = note
        if (stored == null) {
            last = library.newNote(deck, templateId, fields)
            added++
            values = HashMap()
            store.putMeta("last_template", templateId)
            store.putMeta("last_deck", deck)
        } else {
            note = library.saveNote(stored.copy(deckId = deck, templateId = templateId, fields = fields))
        }
        app.rondo.syncSoon()
        then()
    }

    /** Takes back the note just added, and puts its fields back to change them. */
    fun undoAdd() = edit {
        val n = last ?: return@edit
        // Not synced yet: it never existed anywhere else, so it goes without a trace.
        store.q.dropUnsyncedNote(n.id, n.v)
        if (store.note(n.id) == null) store.q.dropCards(listOf(n.id)) else library.deleteNotes(listOf(n.id))
        values = HashMap(n.fields)
        templateId = n.templateId
        deckId = n.deckId
        added--
        last = null
    }

    fun delete(then: () -> Unit) = act {
        noteId?.let { library.deleteNotes(listOf(it)) }
        app.rondo.syncSoon()
        then()
    }
}

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
