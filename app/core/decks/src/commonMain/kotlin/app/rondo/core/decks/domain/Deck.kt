package app.rondo.core.decks.domain

import app.rondo.core.kernel.domain.ErrorCode
import app.rondo.core.kernel.domain.Position
import kotlin.uuid.Uuid

/** A deck: its place among its siblings, its look, and its limits a day. */
data class Deck(
    val id: String,
    val name: String,
    val position: String,
    val color: Int,
    val newPerDay: Int,
    val reviewsPerDay: Int,
    /** Percent. */
    val retention: Int,
    /** Sub-decks open in order. */
    val gated: Boolean,
    /** Percent of the deck before this one that must be learned. */
    val unlockAt: Int,
    val v: Long,
    /** Null: its author removed it, and it's read-only. */
    val ownerId: String? = null,
    val parentId: String? = null,
    val icon: String? = null,
    val description: String? = null,
    /** BCP 47. */
    val language: String? = null,
    val deletedAt: Long? = null,
) {
    /** A new deck, as every client makes them: 20 new cards and 200 reviews a day, 90% retention, levels off. */
    constructor(name: String, position: String, v: Long, ownerId: String?, parentId: String?) : this(
        id = Uuid.generateV7().toString(), name = name, position = position, color = 0, newPerDay = 20,
        reviewsPerDay = 200, retention = 90, gated = false, unlockAt = 100, v = v, ownerId = ownerId,
        parentId = parentId,
    )

    /** Why [by] may not make this the deck that was [stored] (null: a new one), or null. */
    fun check(by: Access, stored: Deck?): ErrorCode? {
        if (Uuid.parseOrNull(id) == null) return ErrorCode.INVALID
        if (name.isBlank() || name.length > 100 || !Position(position).valid || color !in 0..7 ||
            (icon?.length ?: 0) > 16 || (language?.length ?: 0) > 35 || (description?.length ?: 0) > 2000 ||
            newPerDay !in NEW_PER_DAY || reviewsPerDay !in REVIEWS_PER_DAY || retention !in RETENTION ||
            unlockAt !in 50..100 || unlockAt % 10 != 0
        ) {
            return ErrorCode.INVALID
        }
        if (stored != null) {
            if (stored.ownerId == null) return ErrorCode.READ_ONLY
            if (stored.ownerId != ownerId) return ErrorCode.OWNER_MISMATCH
            by.canWrite(stored.id)?.let { return it }
            // A borrower can't move or delete the deck lent to them, nor take a deck out of it.
            val borrowed = stored.ownerId != by.actor
            val movedOrDeleted = parentId != stored.parentId || deletedAt != null
            if (borrowed && stored.id in by.lends && movedOrDeleted) return ErrorCode.FORBIDDEN
            if (borrowed && parentId == null && stored.parentId != null) return ErrorCode.FORBIDDEN
        } else if (parentId == null) {
            if (ownerId != by.actor) return ErrorCode.OWNER_MISMATCH
        }
        if (parentId != null && (stored == null || parentId != stored.parentId)) {
            val parent = by.tree[parentId] ?: return ErrorCode.NOT_FOUND
            if (parent.ownerId != ownerId) return ErrorCode.OWNER_MISMATCH
            if (by.tree.dead(parent.id)) return ErrorCode.NOT_FOUND
            by.canWrite(parent.id)?.let { return it }
            val above = listOf(parent) + by.tree.ancestors(parent.id)
            if (above.any { it.id == id }) return ErrorCode.CYCLE
            if (above.size >= 32) return ErrorCode.INVALID
        }
        return null
    }

    companion object {
        /** How many new cards a day a deck (or a smart deck) can give. */
        val NEW_PER_DAY = 0..9999

        /** How many reviews a day a deck (or a smart deck) can give. */
        val REVIEWS_PER_DAY = 0..99999

        /** The retention a deck can aim for, in percent. */
        val RETENTION = 70..99
    }
}
