package app.rondo.core.sync.infrastructure

import app.rondo.core.decks.infrastructure.DeckRecord
import app.rondo.core.kernel.infrastructure.Record
import app.rondo.core.notes.infrastructure.NoteRecord
import app.rondo.core.notes.infrastructure.TemplateRecord
import app.rondo.core.study.infrastructure.EventRecord
import app.rondo.core.study.infrastructure.SettingsRecord
import app.rondo.core.study.infrastructure.SmartDeckRecord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * What a device and the sync server say to each other (`Rondo-Protocol: [VERSION]`): a device pushes
 * its changes as records, in the order they're applied (a deck's parent before it, a note type
 * before its notes), and pulls each stream's changes since the last cursor it saw. Every message is
 * written with [json].
 */
sealed class Message {
    companion object {
        /** The protocol spoken: the server refuses apps that speak an older one. */
        const val VERSION = "1"

        /** Records' JSON, knowing every kind of record: a list that mixes kinds tags each with its own. */
        val json = Json(Record.json) {
            serializersModule = SerializersModule {
                polymorphic(Record::class) {
                    subclass(DeckRecord::class)
                    subclass(TemplateRecord::class)
                    subclass(NoteRecord::class)
                    subclass(EventRecord::class)
                    subclass(SettingsRecord::class)
                    subclass(SmartDeckRecord::class)
                }
            }
        }
    }
}

@Serializable
class Push(val records: List<Record>) : Message()

@Serializable
class Pushed(val accepted: Int, val rejected: List<Rejection>) : Message()

/** A record the server refused: [entity] its kind (deck, template, note, event, settings, smart_deck), [code] why. */
@Serializable
class Rejection(val entity: String, val id: String, val code: String)

@Serializable
class Pull(val streams: List<Stream>) : Message()

/** Where a device is in a stream: `me`, or a lent deck's id. */
@Serializable
class Stream(val key: String, val cursor: Long, val fingerprint: String? = null)

@Serializable
class Pulled(val lends: List<Lend>, val streams: List<Page>) : Message()

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
class Fetch(val decks: List<String> = emptyList(), val templates: List<String> = emptyList()) : Message()

@Serializable
class Fetched(val records: List<Record>) : Message()
