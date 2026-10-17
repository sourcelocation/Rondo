package app.rondo.core.notes.domain

import app.rondo.core.decks.domain.Access
import app.rondo.core.kernel.domain.ErrorCode
import kotlin.uuid.Uuid

/** A note: its fields' text by field id, in a deck, of a note type; its cards come from the type. */
data class Note(
    val id: String,
    val deckId: String,
    val templateId: String,
    /** Field id to content: markup for text, a hash for media, an [Occlusion] for masks. */
    val fields: Map<String, String>,
    val v: Long,
    val ownerId: String? = null,
    val deletedAt: Long? = null,
    /** As [Tags] keeps them: sorted, single spaces. */
    val tags: String = "",
) {
    /** Why [by] may not make this the note that was [stored] (null: a new one) in [template], or null. */
    fun check(by: Access, stored: Note?, template: Template?): ErrorCode? {
        if (Uuid.parseOrNull(id) == null) return ErrorCode.INVALID
        if (!tagsWellFormed) return ErrorCode.INVALID
        if (stored != null) {
            if (stored.ownerId == null) return ErrorCode.READ_ONLY
            if (stored.ownerId != ownerId) return ErrorCode.OWNER_MISMATCH
            by.canWrite(stored.deckId)?.let { return it }
        }
        val deck = by.tree[deckId] ?: return ErrorCode.NOT_FOUND
        if (by.tree.dead(deck.id) && deletedAt == null) return ErrorCode.NOT_FOUND
        if (deck.ownerId != ownerId) {
            return if (deck.ownerId == null) ErrorCode.READ_ONLY else ErrorCode.OWNER_MISMATCH
        }
        by.canWrite(deck.id)?.let { return it }
        template ?: return ErrorCode.NOT_FOUND
        if (!template.builtin && template.ownerId != ownerId) return ErrorCode.OWNER_MISMATCH
        // Anki's notes (kept 1:1) are read-only: neither their fields nor their note type change.
        val changed = stored != null && (stored.fields != fields || stored.templateId != templateId)
        if (template is AnkiTemplate && changed) return ErrorCode.READ_ONLY
        if (template.problem(fields) != null) return ErrorCode.INVALID
        return null
    }

    /** What the rules ask of its tags: tidy tags, single spaces, within the limits. */
    private val tagsWellFormed: Boolean get() {
        if (tags.isEmpty()) return true
        val list = tags.split(' ')
        return list.size <= Tags.MAX_COUNT && list.all { it.length <= Tags.MAX_LENGTH && Tag(it).text == it }
    }
}
