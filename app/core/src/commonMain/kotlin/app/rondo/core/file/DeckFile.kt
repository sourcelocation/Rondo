package app.rondo.core.file

import app.rondo.core.Deck
import app.rondo.core.Event
import app.rondo.core.Note
import app.rondo.core.Template

/**
 * A deck written down as a file: read into [contents], or written from them into [files]. A file
 * only reads and writes; where its decks go, and what's already there, is the importer's.
 */
interface DeckFile {
    /** The decks it holds, each with everything under it. */
    suspend fun contents(): List<Contents>

    /** A media file it holds, by hash. */
    suspend fun media(hash: String): MediaFile?

    /** It, as files by name: zipping them is the caller's. */
    suspend fun files(): Map<String, ByteArray>
}

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
    /** A note in the deck, with its note type and the events of its cards. */
    class Entry(val note: Note, val template: Template, val events: List<Event> = emptyList())

    /** This deck and every deck under it, parents first. */
    val all: List<Contents> get() = listOf(this) + decks.flatMap { it.all }

    /** The same decks, each as [deck] makes it, and each note as [entry] does. */
    fun map(deck: (Deck) -> Deck = { it }, entry: (Entry) -> Entry = { it }): Contents =
        Contents(deck(this.deck), notes.map(entry), decks.map { it.map(deck, entry) }, events)
}

/** A picture or sound: named by the SHA-256 of its [bytes], in hex. */
class MediaFile(val hash: String, val mime: String, val bytes: ByteArray)
