package app.rondo.core.decks.infrastructure

import app.rondo.core.kernel.infrastructure.Record
import kotlin.test.Test
import kotlin.test.assertEquals

/** A deck's record reads and writes the JSON every app and the server already store, through the domain and back. */
class DeckRecordTest {
    private val id = "0190f3e2-6b1a-7c3d-8e4f-123456789abc"
    private val other = "0190f3e2-6b1a-7c3d-8e4f-cba987654321"

    @Test
    fun aDeckRoundTrips() {
        val json = """{"id":"$id","owner_id":"$other","parent_id":"$other","name":"HSK 1","position":"V","icon":"🀄",
            "color":3,"language":"zh","new_per_day":20,"reviews_per_day":200,"retention":90,"gated":true,
            "unlock_at":90,"v":17}"""
        val record = Record.json.decodeFromString(DeckRecord.serializer(), json)
        val again = Record.json.encodeToString(DeckRecord.serializer(), DeckRecord(record.deck()))
        assertEquals(Record.json.parseToJsonElement(json), Record.json.parseToJsonElement(again))
    }
}
