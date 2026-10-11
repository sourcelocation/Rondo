package app.rondo.core.sync

import app.rondo.core.record.Record
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a device and the sync server say to each other (`Rondo-Protocol: 1`): a device pushes its
 * changes as records, in the order they're applied (a deck's parent before it, a note type before
 * its notes), and pulls each stream's changes since the last cursor it saw.
 */
@Serializable
class Push(val records: List<Record>)

@Serializable
class Pushed(val accepted: Int, val rejected: List<Rejection>)

/** A record the server refused: [entity] its kind (deck, template, note, event, settings, smart_deck), [code] why. */
@Serializable
class Rejection(val entity: String, val id: String, val code: String)

@Serializable
class Pull(val streams: List<Stream>)

/** Where a device is in a stream: `me`, or a lent deck's id. */
@Serializable
class Stream(val key: String, val cursor: Long, val fingerprint: String? = null)

@Serializable
class Pulled(val lends: List<Lend>, val streams: List<Page>)

/** A deck lent to the device's account: [role] 1 viewer, 2 editor. */
@Serializable
class Lend(
    @SerialName("deck_id") val deckId: String,
    val role: Int,
    @SerialName("owner_id") val ownerId: String? = null,
    @SerialName("owner_name") val ownerName: String? = null,
)

/**
 * A stream's changes after a cursor, up to [cursor]: [more] when there are more, [resync] when the
 * device must start the stream over. [deckIds]: every deck in the stream, on its last page.
 */
@Serializable
class Page(
    val key: String,
    val records: List<Record>,
    val cursor: Long,
    val more: Boolean,
    val resync: Boolean,
    val fingerprint: String? = null,
    @SerialName("deck_ids") val deckIds: List<String>? = null,
)

/** Decks and note types to read whole: ones a device was missing. */
@Serializable
class Fetch(val decks: List<String> = emptyList(), val templates: List<String> = emptyList())

@Serializable
class Fetched(val records: List<Record>)
