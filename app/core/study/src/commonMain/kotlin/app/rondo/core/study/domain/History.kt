package app.rondo.core.study.domain

/**
 * A learner's events by what they're about: a note's, a card's or a deck's, each with the voids
 * that take one of them back (a void is about the event it voids).
 */
class History(events: Collection<Event>) {
    private val voids = events.filterIsInstance<Event.Void>().groupBy { it.subjectId }
    private val about = events.filter { it !is Event.Void }.groupBy { it.subjectId }

    /** The events about [subject], and the voids of any of them. */
    fun of(subject: String): List<Event> = about[subject].orEmpty().let { own ->
        own +
            own.flatMap { voids[it.id].orEmpty() }
    }

    /** Card [card] of note [note], with its events. */
    fun card(note: String, card: Int): Card {
        val own = about[note].orEmpty().filter { it.card == card }
        return Card(note, card, own + own.flatMap { voids[it.id].orEmpty() })
    }
}
