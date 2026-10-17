package app.rondo.client

import app.rondo.core.decks.domain.Tree
import app.rondo.core.notes.domain.AnkiTemplate
import app.rondo.core.notes.domain.Block
import app.rondo.core.notes.domain.FieldDef
import app.rondo.core.notes.domain.Note
import app.rondo.core.notes.domain.Occlusion
import app.rondo.core.notes.domain.RondoTemplate
import app.rondo.core.notes.domain.Tags
import app.rondo.core.notes.domain.Template
import kotlin.js.JsExport

/** An occlusion mask, in fractions of the image. */
@JsExport
class Box internal constructor(val group: Int, val x: Double, val y: Double, val w: Double, val h: Double)

/**
 * One field as the editor shows it: markup for text, a media hash, or an image with masks.
 * [each]: its clozes make the note's cards. [side]: front or back, where the first card shows it,
 * or none when no card does;
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
    /** Another note already has its first field; it can still be added. */
    val duplicate: String?,
    /** Notes added since the editor opened. */
    val added: Int,
    /** The note just added can be taken back. */
    val canUndoAdd: Boolean,
    val gone: Boolean,
    val tags: Array<String>,
    /** Tags can change, on Anki's notes too: they aren't fields. */
    val canTag: Boolean,
)

/** Adding notes one after another (noteId null), or editing one: fields, type and deck. */
@JsExport
class EditorScreen internal constructor(
    override val app: App,
    private var noteId: String?,
    private var deckId: String?,
) : Screen<EditorState>() {
    override val live get() = false
    private val library get() = app.rondo.library
    private var note: Note? = null
    private var templateId = Template.BASIC.id
    private var values = HashMap<String, String>()
    private var tags = Tags.NONE
    private var added = 0
    private var last: Note? = null
    private var ready = false

    override suspend fun load(): EditorState {
        if (!ready) {
            ready = true
            note = noteId?.let { library.note(it) }
            note?.let {
                deckId = it.deckId
                templateId = it.templateId
                values = HashMap(it.fields)
                tags = Tags(it.tags)
            } ?: run {
                templateId =
                    library.remembered(LAST_TEMPLATE)?.takeIf { library.template(it) != null } ?: Template.BASIC.id
                deckId = deckId ?: library.remembered(LAST_DECK)
            }
        }
        val tree = library.tree()
        val access = library.access()
        val writable = tree.byId.values.filter { !tree.dead(it.id) && access.canWrite(it.id) == null }
        if (deckId == null || writable.none { it.id == deckId }) {
            deckId = if (note == null) writable.minByOrNull { tree.path(it.id).size }?.id else deckId
        }
        val template = library.template(templateId)
        val gone = (noteId != null && (note == null || note?.deletedAt != null)) || template == null
        val draft = Note(noteId ?: "", deckId ?: "", templateId, values.filterValues { it.isNotBlank() }, 0)
        val canTag = note == null || access.canWrite(note!!.deckId) == null
        val anki = template is AnkiTemplate
        val readOnly = anki || !canTag
        val choices = library.templates().values.filter {
            it.deletedAt == null && it is RondoTemplate && (it.builtin || it.ownerId == library.me)
        }
        val cards = template?.let { samples(it, draft) } ?: emptyArray()
        val problem = when {
            template == null || readOnly -> null
            deckId == null -> strings.makeDeckFirst
            template.problem(draft.fields) != null -> strings.error("invalid")
            cards.isEmpty() -> missing(template)
            else -> null
        }
        val duplicate = template?.takeIf { !readOnly && deckId != null }?.let { duplicate(it, tree, draft) }
        return EditorState(
            noteId, deckId, tree[deckId]?.name, deckId?.let { tree.language(it) }, templateId,
            template?.name.orEmpty(), template?.let { fields(it) } ?: emptyArray(),
            choices(tree, writable).toTypedArray(),
            choices.sortedWith(compareBy({ !it.builtin }, { it.name.lowercase() }))
                .map { Choice(it.id, it.name) }.toTypedArray(),
            readOnly, anki, cards, !gone && (if (anki) canTag else !readOnly && problem == null),
            problem, duplicate, added, last != null, gone, tags.list.toTypedArray(), canTag && !gone,
        )
    }

    private fun fields(t: Template): Array<FieldValue> {
        val cards = (t as? RondoTemplate)?.cards.orEmpty()
        val first = cards.firstOrNull()
        val onCards = cards.flatMap { it.front + it.back }.mapNotNull {
            (it as? Block.Field)?.id
                ?: (it as? Block.TypeIn)?.id
        }.toSet()
        return t.fields.map { f ->
            val v = values[f.id.toString()].orEmpty()
            val occlusion = f.kind == FieldDef.Kind.OCCLUSION
            val masks = if (occlusion) {
                Occlusion(v).masks.orEmpty().map {
                    Box(it.group, it.x, it.y, it.w, it.h)
                }
            } else {
                emptyList()
            }
            val front = first?.front.orEmpty().filterIsInstance<Block.Field>().firstOrNull { it.id == f.id }
            val block = front ?: first?.back.orEmpty().filterIsInstance<Block.Field>().firstOrNull { it.id == f.id }
            FieldValue(
                f.id.toString(), f.name, f.kind.value, v, if (occlusion && v.isNotBlank()) Occlusion(v).image else null,
                masks.toTypedArray(), cards.any { it.each == f.id },
                when {
                    front != null -> "front"
                    f.id in onCards -> "back"
                    else -> "none"
                },
                (block?.size ?: Block.Size.NORMAL).value, f.tts,
            )
        }.toTypedArray()
    }

    /** Which top-level deck already has a note like [draft], said as a warning. */
    private suspend fun duplicate(t: Template, tree: Tree, draft: Note): String? {
        val key = t.key(draft.fields) ?: return null
        val found = library.repeats(t, draft.deckId, key).any { it.id != noteId && t.key(it.fields) == key }
        if (!found) return null
        val root = tree.ancestors(draft.deckId).lastOrNull() ?: tree[draft.deckId]
        return strings.alreadyThere(t.fields.first().name, root?.name.orEmpty())
    }

    /** What a note of [t] still needs before it makes a card. */
    private fun missing(t: Template): String {
        val cards = (t as? RondoTemplate)?.cards.orEmpty()
        val each = cards.singleOrNull()?.each?.let { id -> t.fields.firstOrNull { it.id == id } }
        if (each?.kind == FieldDef.Kind.OCCLUSION) {
            return if (values[each.id.toString()].isNullOrBlank()) strings.addPicture else strings.drawMasks
        }
        if (each != null) return strings.makeBlank
        val card = cards.firstOrNull()
        // What the card asks for: its first way of existing, or every field its front shows.
        val needed =
            card?.requires?.firstOrNull() ?: card?.front.orEmpty().filterIsInstance<Block.Field>().map { it.id }
        val empty = needed.firstOrNull { values[it.toString()].isNullOrBlank() } ?: needed.firstOrNull()
        return strings.fillIn(t.fields.firstOrNull { it.id == empty }?.name ?: strings.front)
    }

    private fun edit(block: suspend () -> Unit) = act {
        block()
        show(load())
    }

    fun setDeck(id: String) = edit { deckId = id }

    /** Switches the note type, keeping what fits: fields in the same place and of the same kind. */
    fun setTemplate(id: String) = edit {
        val from = library.template(templateId)?.fields.orEmpty()
        val to = library.template(id)?.fields ?: return@edit
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

    /** Adds tags, as typed: separated by spaces. They stay for the next note added. */
    fun addTags(text: String) = edit { tags += listOf(text) }

    fun removeTag(tag: String) = edit { tags -= listOf(tag) }

    /** Stores a file (picked, dropped, pasted or recorded) in the field; an occlusion image starts without masks. */
    fun attach(fieldId: String, bytes: ByteArray, mime: String) = edit {
        val hash = app.rondo.media.add(bytes, mime)
        val kind = library.template(templateId)?.fields?.firstOrNull { it.id.toString() == fieldId }?.kind
        values[fieldId] = if (kind == FieldDef.Kind.OCCLUSION) Occlusion(hash, emptyList()).text else hash
    }

    /** Empties a field: removes its picture or sound. */
    fun clear(fieldId: String) = edit { values.remove(fieldId) }

    /** An occlusion field's masks, five numbers each: group, x, y, width, height (fractions). */
    fun setMasks(fieldId: String, masks: Array<Double>) = edit {
        val image = Occlusion(values[fieldId].orEmpty()).image.takeIf { Template.HASH.matches(it) } ?: return@edit
        values[fieldId] = Occlusion(
            image,
            masks.toList().chunked(5).filter { it.size == 5 }
                .map { (group, x, y, w, h) -> Occlusion.Rect(group.toInt(), x, y, w, h) },
        ).text
    }

    /** Adds the note (and clears the fields for the next) or saves the one being edited; then [then]. */
    fun save(then: () -> Unit) = edit {
        val template = library.template(templateId) ?: return@edit
        val known = template.fields.map { it.id.toString() }.toSet()
        val fields = values.filter { (k, v) -> k in known && v.isNotBlank() }
        val deck = deckId ?: return@edit
        val stored = note
        if (stored == null) {
            last = library.newNote(deck, templateId, fields, tags)
            added++
            values = HashMap()
            library.remember(LAST_TEMPLATE, templateId)
            library.remember(LAST_DECK, deck)
        } else if (template is AnkiTemplate) {
            // Anki's notes keep their fields: only their tags change here.
            note = library.saveNote(stored.copy(tags = tags.text))
        } else {
            val changed = stored.copy(deckId = deck, templateId = templateId, fields = fields, tags = tags.text)
            note = library.saveNote(changed)
        }
        app.rondo.syncSoon()
        then()
    }

    /** Takes back the note just added, and puts its fields back to change them. */
    fun undoAdd() = edit {
        val n = last ?: return@edit
        // Not synced yet: it never existed anywhere else, so it goes without a trace.
        library.unadd(n)
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

    private companion object {
        const val LAST_TEMPLATE = "last_template"
        const val LAST_DECK = "last_deck"
    }
}
