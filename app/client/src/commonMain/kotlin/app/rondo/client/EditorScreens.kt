package app.rondo.client

import app.rondo.core.CardSide
import app.rondo.core.Occlusion
import app.rondo.core.Tags
import app.rondo.core.Templates
import app.rondo.core.model.FieldDef
import app.rondo.core.model.Note
import app.rondo.core.model.Template
import kotlin.js.JsExport

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
    val tags: Array<String>,
    /** Tags can change, on Anki's notes too: they aren't fields. */
    val canTag: Boolean,
)

@JsExport
class Sample internal constructor(val front: CardSide, val back: CardSide)

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
    private var tags = Tags.NONE
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
                tags = Tags.of(it)
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
        val canTag = note == null || access.canWrite(note!!.deckId) == null
        val anki = template?.kind == Templates.ANKI
        val readOnly = anki || !canTag
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
            readOnly, anki, cards, !gone && (if (anki) canTag else !readOnly && problem == null),
            problem, added, last != null, gone, tags.list.toTypedArray(), canTag && !gone,
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

    /** Adds tags, as typed: separated by spaces. They stay for the next note added. */
    fun addTags(text: String) = edit { tags += listOf(text) }

    fun removeTag(tag: String) = edit { tags -= listOf(tag) }

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
            last = library.newNote(deck, templateId, fields, tags)
            added++
            values = HashMap()
            store.putMeta("last_template", templateId)
            store.putMeta("last_deck", deck)
        } else if (template.kind == Templates.ANKI) {
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
