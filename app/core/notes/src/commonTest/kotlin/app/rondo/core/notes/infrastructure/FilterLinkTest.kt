package app.rondo.core.notes.infrastructure

import app.rondo.core.notes.domain.Filter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class FilterLinkTest {
    private val deck = Uuid.generateV7().toString()

    @Test
    fun filterLinks() {
        val filter = Filter(text = "valve", deckIds = listOf(deck), tags = listOf("Cardio"), marked = true)
        assertEquals(filter, FilterLink(FilterLink(filter).params).filter)
        assertEquals(emptyMap(), FilterLink(Filter()).params)
        // What a link says that a filter can't hold is left out, so what it opens is always valid.
        val odd = FilterLink(
            mapOf(
                "q" to listOf("  "),
                "deck" to listOf("x"),
                "tag" to listOf("two words"),
                "state" to listOf("lost"),
            ),
        ).filter
        assertEquals(Filter(tags = listOf("two_words")), odd)
        assertEquals(Filter(deckIds = listOf(deck)), Filter().withDeck(deck).withMarked(false).withText(" "))
    }
}
