package app.rondo.core.decks.domain

import kotlin.math.min

/** Per deck (its own notes, not its sub-decks): what can be studied today and what was. */
data class DeckLoad(
    val newAvailable: Int = 0,
    val learningDue: Int = 0,
    val reviewDue: Int = 0,
    val newToday: Int = 0,
    val reviewsToday: Int = 0,
    val notes: Int = 0,
    val learnedNotes: Int = 0,
) {
    /**
     * What a set of cards picked by a filter offers today, within [newPerDay] and [reviewsPerDay]
     * (new cards in open levels only): the cards answered today count wherever they were studied.
     */
    fun counts(newPerDay: Int, reviewsPerDay: Int): Counts = Counts(
        min(newAvailable, (newPerDay - newToday).coerceAtLeast(0)),
        learningDue,
        min(reviewDue, (reviewsPerDay - reviewsToday).coerceAtLeast(0)),
    )
}

data class Counts(val new: Int, val learning: Int, val review: Int) {
    val total: Int get() = new + learning + review
}

/** A deck's place in its levels: [ring] is a whole percent, 100 only when true. */
data class Level(val open: Boolean, val ring: Int, val index: Int = 0, val of: Int = 0)
