package app.rondo.core.decks.infrastructure

import app.rondo.core.decks.domain.Deck
import app.rondo.core.kernel.infrastructure.Record
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A row of `decks`. */
@Serializable
@SerialName("deck")
class DeckRecord(
    override val id: String,
    @SerialName("owner_id") val ownerId: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    val name: String,
    val position: String,
    val icon: String? = null,
    val color: Int,
    val description: String? = null,
    val language: String? = null,
    @SerialName("new_per_day") val newPerDay: Int,
    @SerialName("reviews_per_day") val reviewsPerDay: Int,
    val retention: Int,
    val gated: Boolean,
    @SerialName("unlock_at") val unlockAt: Int,
    override val v: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
) : Record() {
    constructor(d: Deck) : this(
        d.id, d.ownerId, d.parentId, d.name, d.position, d.icon, d.color, d.description, d.language,
        d.newPerDay, d.reviewsPerDay, d.retention, d.gated, d.unlockAt, d.v, d.deletedAt,
    )

    fun deck() = Deck(
        id, name, position, color, newPerDay, reviewsPerDay, retention, gated, unlockAt, v,
        ownerId, parentId, icon, description, language, deletedAt,
    )
}
