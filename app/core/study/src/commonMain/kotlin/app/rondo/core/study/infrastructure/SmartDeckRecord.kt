package app.rondo.core.study.infrastructure

import app.rondo.core.kernel.infrastructure.Record
import app.rondo.core.notes.domain.Filter
import app.rondo.core.study.domain.SmartDeck
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A row of `smart_decks`. */
@Serializable
@SerialName("smart_deck")
class SmartDeckRecord(
    override val id: String,
    @SerialName("owner_id") val ownerId: String,
    val name: String,
    val position: String,
    val icon: String? = null,
    val color: Int,
    val filter: FilterRecord,
    @SerialName("new_per_day") val newPerDay: Int,
    @SerialName("reviews_per_day") val reviewsPerDay: Int,
    override val v: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
) : Record() {
    constructor(s: SmartDeck) : this(
        s.id, s.ownerId, s.name, s.position, s.icon, s.color, FilterRecord(s.filter), s.newPerDay, s.reviewsPerDay,
        s.v, s.deletedAt,
    )

    fun smartDeck() =
        SmartDeck(id, ownerId, name, position, color, filter.filter(), newPerDay, reviewsPerDay, v, icon, deletedAt)
}

/** A smart deck's filter, stored inside it. */
@Serializable
class FilterRecord(
    val text: String? = null,
    @SerialName("deck_ids") val deckIds: List<String>? = null,
    @SerialName("template_ids") val templateIds: List<String>? = null,
    val tags: List<String>? = null,
    val state: String? = null,
    val marked: Boolean? = null,
) {
    constructor(f: Filter) : this(f.text, f.deckIds, f.templateIds, f.tags, f.state, f.marked)

    fun filter() = Filter(text, deckIds, templateIds, tags, state, marked)
}
