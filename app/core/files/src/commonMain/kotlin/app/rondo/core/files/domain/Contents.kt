package app.rondo.core.files.domain

import app.rondo.core.decks.domain.Deck
import app.rondo.core.decks.domain.Tree
import app.rondo.core.notes.domain.Note
import app.rondo.core.notes.domain.Template
import app.rondo.core.study.domain.Event
import app.rondo.core.study.domain.History

/**
 * A deck in hand, with what's in it: its notes, each with its note type and its cards' events, and
 * the decks under it. What a file holds, or what's copied or exported. [events] are the deck's own
 * (a level opened by hand).
 */
class Contents(
    val deck: Deck,
    val notes: List<Entry>,
    val decks: List<Contents> = emptyList(),
    val events: List<Event> = emptyList(),
) {
    /**
     * [deck] of [tree] in hand, with its live sub-decks: in each, its [notes] (by deck) whose note
     * type is among [templates], with their events from [history].
     */
    constructor(
        deck: Deck,
        tree: Tree,
        notes: Map<String, List<Note>>,
        templates: Map<String, Template>,
        history: History,
    ) : this(
        deck,
        notes[deck.id].orEmpty().mapNotNull { n -> templates[n.templateId]?.let { Entry(n, it, history.of(n.id)) } },
        tree.children(deck.id).map { Contents(it, tree, notes, templates, history) },
        history.of(deck.id),
    )

    /** A note in the deck, with its note type and the events of its cards. */
    class Entry(val note: Note, val template: Template, val events: List<Event> = emptyList())

    /** This deck and every deck under it, parents first. */
    val all: List<Contents> get() = listOf(this) + decks.flatMap { it.all }

    /** The same decks, each as [deck] makes it, and each note as [entry] does. */
    fun map(deck: (Deck) -> Deck = { it }, entry: (Entry) -> Entry = { it }): Contents =
        Contents(deck(this.deck), notes.map(entry), decks.map { it.map(deck, entry) }, events)
}
