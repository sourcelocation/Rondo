package app.rondo.core.notes.infrastructure

import app.rondo.core.kernel.infrastructure.Record
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.KSerializer

/** Notes' and note types' records read and write the JSON every app and the server already store, through the domain and back. */
class NoteRecordsTest {
    private val id = "0190f3e2-6b1a-7c3d-8e4f-123456789abc"
    private val other = "0190f3e2-6b1a-7c3d-8e4f-cba987654321"

    private fun <T> roundTrip(serializer: KSerializer<T>, json: String, through: (T) -> T) {
        val record = Record.json.decodeFromString(serializer, json)
        val again = Record.json.encodeToString(serializer, through(record))
        assertEquals(Record.json.parseToJsonElement(json), Record.json.parseToJsonElement(again), json)
    }

    @Test
    fun notes() {
        roundTrip(
            NoteRecord.serializer(),
            """{"id":"$id","owner_id":"$other","deck_id":"$other","template_id":"$other",
                "fields":{"1":"爱","2":"love"},"v":3,"tags":"HSK1 verbs"}""",
        ) { NoteRecord(it.note()) }
    }

    @Test
    fun noteTypesOfBothKinds() {
        roundTrip(
            TemplateRecord.serializer(),
            """{"id":"$id","owner_id":"$other","name":"Mine","kind":1,"v":1,"def":{
                "fields":[{"id":1,"name":"Front","kind":"text","tts":true},{"id":2,"name":"Picture","kind":"image"}],
                "cards":[{"id":1,"name":"Card","front":[{"field":1,"size":"large"},{"typein":1}],
                  "back":[{"field":1},{"divider":true},{"label":"Look:","align":"start"},{"field":2,"size":"small"}],
                  "requires":[[1],[2]]}]}}""",
        ) { TemplateRecord(assertNotNull(it.template())) }
        roundTrip(
            TemplateRecord.serializer(),
            """{"id":"$id","owner_id":"$other","name":"Anki","kind":2,"v":1,"def":{
                "fields":[{"id":1,"name":"Front","kind":"text"}],
                "cards":[{"id":1,"name":"Card 1","front":[],"back":[],"anki_front":"{{Front}}","anki_back":"{{FrontSide}}"}],
                "css":".card { color: red }"}}""",
        ) { TemplateRecord(assertNotNull(it.template())) }
        // A kind of block, field or note type a newer app made isn't read as something else.
        val unknown = """{"id":"$id","name":"New","kind":3,"v":1,"def":{"fields":[],"cards":[]}}"""
        assertNull(Record.json.decodeFromString(TemplateRecord.serializer(), unknown).template())
        val video = """{"id":"$id","name":"V","kind":1,"v":1,
            "def":{"fields":[{"id":1,"name":"F","kind":"video"}],"cards":[]}}"""
        assertNull(Record.json.decodeFromString(TemplateRecord.serializer(), video).template())
    }
}
