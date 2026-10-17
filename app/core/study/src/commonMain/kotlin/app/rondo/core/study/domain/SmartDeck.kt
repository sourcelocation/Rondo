package app.rondo.core.study.domain

import app.rondo.core.decks.domain.Access
import app.rondo.core.decks.domain.Deck
import app.rondo.core.kernel.domain.ErrorCode
import app.rondo.core.kernel.domain.Position
import app.rondo.core.notes.domain.Filter
import kotlin.uuid.Uuid

/** A learner's own saved filter, studied like a deck, within its own limits. Never lent or published. */
data class SmartDeck(
    val id: String,
    val ownerId: String,
    val name: String,
    /** A fractional index among the owner's smart decks. */
    val position: String,
    val color: Int,
    val filter: Filter,
    val newPerDay: Int,
    val reviewsPerDay: Int,
    val v: Long,
    val icon: String? = null,
    val deletedAt: Long? = null,
) {
    /** A new smart deck, with a new deck's daily limits. */
    constructor(name: String, filter: Filter, position: String, v: Long, ownerId: String) : this(
        id = Uuid.generateV7().toString(), ownerId = ownerId, name = name, position = position, color = 0,
        filter = filter, newPerDay = 20, reviewsPerDay = 200, v = v,
    )

    /** Why [by] may not make this the smart deck that was [stored]: only its owner makes and changes it. */
    fun check(by: Access, stored: SmartDeck?): ErrorCode? = when {
        Uuid.parseOrNull(id) == null -> ErrorCode.INVALID

        stored != null && stored.ownerId != ownerId -> ErrorCode.OWNER_MISMATCH

        ownerId != by.actor -> ErrorCode.FORBIDDEN

        name.isBlank() || name.length > 100 || !Position(position).valid || color !in 0..7 ||
            (icon?.length ?: 0) > 16 || newPerDay !in Deck.NEW_PER_DAY || reviewsPerDay !in Deck.REVIEWS_PER_DAY ||
            !filter.valid -> ErrorCode.INVALID

        else -> null
    }
}
