package app.rondo.core.notes.infrastructure

import app.rondo.core.kernel.infrastructure.Record
import app.rondo.core.notes.domain.Note
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A note: [fields] by field id; [tags] absent from an app that predates them. */
@Serializable
@SerialName("note")
class NoteRecord(
    override val id: String,
    @SerialName("owner_id") val ownerId: String? = null,
    @SerialName("deck_id") val deckId: String,
    @SerialName("template_id") val templateId: String,
    val fields: Map<String, String>,
    override val v: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
    val tags: String? = null,
) : Record() {
    constructor(n: Note) : this(n.id, n.ownerId, n.deckId, n.templateId, n.fields, n.v, n.deletedAt, n.tags)

    fun note() = Note(id, deckId, templateId, fields, v, ownerId, deletedAt, tags.orEmpty())
}
