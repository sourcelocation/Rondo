package app.rondo.core.files.infrastructure

import app.rondo.core.decks.domain.Deck
import app.rondo.core.files.domain.Contents
import app.rondo.core.files.domain.MediaFile
import app.rondo.core.notes.domain.Note
import app.rondo.core.notes.domain.Template
import app.rondo.core.study.domain.Event
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class RondoFileTest {
    private fun newId() = Uuid.generateV7().toString()

    /** Runs [block], which never really suspends: common tests have no event loop. */
    private fun <T> blocking(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
        return result!!.getOrThrow()
    }

    private val deck = Deck("Spanish", "V", 1, "me", null)
    private val verbs = Deck("Verbs", "V", 1, "me", deck.id)
    private val note = Note(newId(), deck.id, Template.BASIC.id, mapOf("1" to "hola"), 2, "me", tags = "greeting")
    private val custom = Template.BASIC.copy(id = newId(), name = "Mine", ownerId = "me")
    private val second = Note(newId(), verbs.id, custom.id, mapOf("1" to "hablar"), 2, "me")
    private val answer = Event.Review(newId(), "me", note.id, 1, 3, 3)
    private val undone = Event.Void(newId(), "me", answer.id, 4)

    private fun written(vararg contents: Contents, media: List<MediaFile> = emptyList()) =
        blocking { RondoFile(contents.toList(), media, 5).files() }

    @Test
    fun aDeckComesBackAsItWent() {
        val files = written(
            Contents(
                deck,
                listOf(Contents.Entry(note, Template.BASIC, listOf(answer, undone))),
                listOf(Contents(verbs, listOf(Contents.Entry(second, custom)))),
            ),
        )
        // Built-in note types are never in it.
        assertEquals(setOf("rondo.json", "decks.json", "templates.json", "notes.json", "events.json"), files.keys)
        val file = RondoFile(files)
        assertEquals(RondoFile.DECK, file.manifest!!.kind)
        assertEquals(listOf(deck.id), file.manifest!!.decks)
        val spanish = blocking { file.contents() }.single()
        assertEquals(deck, spanish.deck)
        val hola = spanish.notes.single()
        assertEquals(note, hola.note)
        assertEquals(Template.BASIC, hola.template)
        // A void travels with the answer it takes back.
        assertEquals(listOf(answer.id, undone.id), hola.events.map { it.id })
        assertEquals(3, (hola.events[0] as Event.Review).rating)
        val sub = spanish.decks.single()
        assertEquals(verbs, sub.deck)
        assertEquals(custom, sub.notes.single().template)
    }

    @Test
    fun whatsDeletedStaysOut() {
        val gone = verbs.copy(deletedAt = 1)
        val inside = Deck("Irregular", "V", 1, "me", gone.id)
        val there = Note(newId(), inside.id, Template.BASIC.id, mapOf("1" to "ir"), 2, "me")
        val irregular = Contents(inside, listOf(Contents.Entry(there, Template.BASIC)))
        val files = written(
            Contents(
                deck,
                listOf(Contents.Entry(note.copy(deletedAt = 1), Template.BASIC)),
                listOf(Contents(gone, emptyList(), listOf(irregular))),
            ),
        )
        val read = blocking { RondoFile(files).contents() }.single()
        assertEquals("Spanish", read.deck.name)
        assertTrue(read.all.flatMap { it.notes }.isEmpty())
        assertEquals(listOf("Spanish"), read.all.map { it.deck.name })
    }

    @Test
    fun theServersExportReadsToo() {
        // As the Go server writes it: other key order, nulls left out, a field from a newer version.
        val files = mapOf(
            "rondo.json" to """{"exported_at":5,"format":1,"kind":"account"}""",
            "decks.json" to """[{"color":0,"gated":false,"id":"${deck.id}","name":"Spanish","new_per_day":20,""" +
                """"owner_id":"me","position":"V","retention":90,"reviews_per_day":200,"unlock_at":100,"v":1,"new":1}]""",
        ).mapValues { it.value.encodeToByteArray() }
        val file = RondoFile(files)
        assertEquals(RondoFile.ACCOUNT, file.manifest!!.kind)
        assertEquals("Spanish", blocking { file.contents() }.single().deck.name)
    }

    @Test
    fun aNewerFormatOrAnythingElseIsNotRead() {
        val newer = mapOf("rondo.json" to """{"exported_at":5,"format":2,"kind":"deck"}""".encodeToByteArray())
        assertNull(RondoFile(newer).manifest)
        assertTrue(blocking { RondoFile(newer).contents() }.isEmpty())
        assertNull(RondoFile(mapOf("rondo.json" to "not json".encodeToByteArray())).manifest)
        assertNull(RondoFile(mapOf("collection.anki2" to ByteArray(0))).manifest)
    }

    @Test
    fun mediaAreNamedByTheirHash() {
        val hash = "a".repeat(64)
        val files = written(Contents(deck, emptyList()), media = listOf(MediaFile(hash, "image/png", byteArrayOf(1))))
        val file = RondoFile(files + ("media/other.png" to byteArrayOf(2)))
        val image = blocking { file.media(hash) }!!
        assertEquals("image/png", image.mime)
        assertEquals(listOf<Byte>(1), image.bytes.toList())
        assertNull(blocking { file.media("other.png") })
    }
}
