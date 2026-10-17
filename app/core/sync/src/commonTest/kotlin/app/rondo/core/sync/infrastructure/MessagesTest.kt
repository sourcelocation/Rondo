package app.rondo.core.sync.infrastructure

import app.rondo.core.decks.infrastructure.DeckRecord
import app.rondo.core.kernel.infrastructure.Record
import app.rondo.core.study.infrastructure.EventRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Sync carries every slice's records, each tagged with its kind, as every app and the server already do. */
class MessagesTest {
    private val id = "0190f3e2-6b1a-7c3d-8e4f-123456789abc"
    private val other = "0190f3e2-6b1a-7c3d-8e4f-cba987654321"

    @Test
    fun aMixedListTagsEachRecordWithItsKind() {
        val deck = """{"id":"$id","name":"D","position":"V","color":0,"new_per_day":20,"reviews_per_day":200,
            "retention":90,"gated":false,"unlock_at":100,"v":1}"""
        val records = Message.json.decodeFromString(
            ListSerializer(Record.serializer()),
            """[{"type":"deck",${deck.trim().removePrefix("{")},
                {"type":"event","id":"$id","user_id":"$other","subject_id":"$other","card":0,"kind":9,"at":5}]""",
        )
        assertEquals(listOf(DeckRecord::class, EventRecord::class), records.map { it::class })
        val written = Message.json.encodeToJsonElement(ListSerializer(Record.serializer()), records) as JsonArray
        assertEquals(listOf("deck", "event"), written.map { (it.jsonObject["type"] as Any).toString().trim('"') })
        assertEquals(id, (written[0] as JsonObject)["id"].toString().trim('"'))
    }

    @Test
    fun aPushReadsBackAsItWent() {
        val push = Push(listOf(EventRecord(id, other, other, 0, EventRecord.UNLOCK, at = 5)))
        val again = Message.json.decodeFromString(
            Push.serializer(),
            Message.json.encodeToString(Push.serializer(), push),
        )
        assertEquals(id, again.records.single().id)
        assertEquals(EventRecord::class, again.records.single()::class)
    }
}
