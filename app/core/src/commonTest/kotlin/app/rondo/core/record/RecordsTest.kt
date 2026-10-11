package app.rondo.core.record

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Records read and write the JSON every app and the server already store, through the domain and back. */
class RecordsTest {
    private val id = "0190f3e2-6b1a-7c3d-8e4f-123456789abc"
    private val other = "0190f3e2-6b1a-7c3d-8e4f-cba987654321"

    private fun <T> roundTrip(serializer: KSerializer<T>, json: String, through: (T) -> T) {
        val record = Record.json.decodeFromString(serializer, json)
        val again = Record.json.encodeToString(serializer, through(record))
        assertEquals(Record.json.parseToJsonElement(json), Record.json.parseToJsonElement(again), json)
    }

    @Test
    fun decksNotesAndSettings() {
        roundTrip(
            DeckRecord.serializer(),
            """{"id":"$id","owner_id":"$other","parent_id":"$other","name":"HSK 1","position":"V","icon":"🀄",
                "color":3,"language":"zh","new_per_day":20,"reviews_per_day":200,"retention":90,"gated":true,
                "unlock_at":90,"v":17}""",
        ) { DeckRecord(it.deck()) }
        roundTrip(
            NoteRecord.serializer(),
            """{"id":"$id","owner_id":"$other","deck_id":"$other","template_id":"$other",
                "fields":{"1":"爱","2":"love"},"v":3,"tags":"HSK1 verbs"}""",
        ) { NoteRecord(it.note()) }
        roundTrip(
            SettingsRecord.serializer(),
            """{"user_id":"$id","grading":4,"theme":0,"text_size":100,"timezone":"Europe/Berlin",
                "reminder_at":1110,"v":9}""",
        ) { SettingsRecord(it.settings()) }
        roundTrip(
            SmartDeckRecord.serializer(),
            """{"id":"$id","owner_id":"$other","name":"Cardio","position":"V","color":0,
                "filter":{"deck_ids":["$other"],"tags":["Cardio"],"state":"review"},
                "new_per_day":20,"reviews_per_day":200,"v":2}""",
        ) { SmartDeckRecord(it.smartDeck()) }
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

    @Test
    fun everyKindOfEvent() {
        val events = """[
            {"id":"$id","user_id":"$other","subject_id":"$other","card":1,"kind":1,"value":3,"at":5,"duration_ms":4200,"due":99},
            {"id":"$id","user_id":"$other","subject_id":"$other","card":1,"kind":2,"at":5},
            {"id":"$id","user_id":"$other","subject_id":"$other","card":1,"kind":3,"at":5},
            {"id":"$id","user_id":"$other","subject_id":"$other","card":1,"kind":4,"at":5},
            {"id":"$id","user_id":"$other","subject_id":"$other","card":1,"kind":5,"at":5},
            {"id":"$id","user_id":"$other","subject_id":"$other","card":1,"kind":6,"value":2,"at":5},
            {"id":"$id","user_id":"$other","subject_id":"$other","card":2,"kind":7,"value":2,"at":5,"due":99,
              "stability":12.5,"difficulty":4.25,"reps":7,"lapses":1},
            {"id":"$id","user_id":"$other","subject_id":"$other","card":0,"kind":8,"at":5},
            {"id":"$id","user_id":"$other","subject_id":"$other","card":0,"kind":9,"at":5}
        ]"""
        roundTrip(ListSerializer(EventRecord.serializer()), events) { list -> list.map { EventRecord(it.event()!!) } }
        val newer = """{"id":"$id","user_id":"$other","subject_id":"$other","card":1,"kind":42,"at":5}"""
        assertNull(Record.json.decodeFromString(EventRecord.serializer(), newer).event())
    }

    @Test
    fun aMixedListTagsEachRecordWithItsKind() {
        val deck = """{"id":"$id","name":"D","position":"V","color":0,"new_per_day":20,"reviews_per_day":200,
            "retention":90,"gated":false,"unlock_at":100,"v":1}"""
        val records = Record.json.decodeFromString(
            ListSerializer(Record.serializer()),
            """[{"type":"deck",${deck.trim().removePrefix("{")},
                {"type":"event","id":"$id","user_id":"$other","subject_id":"$other","card":0,"kind":9,"at":5}]""",
        )
        assertEquals(listOf(DeckRecord::class, EventRecord::class), records.map { it::class })
        val written = Record.json.encodeToJsonElement(ListSerializer(Record.serializer()), records) as JsonArray
        assertEquals(listOf("deck", "event"), written.map { (it.jsonObject["type"] as Any).toString().trim('"') })
        assertEquals(id, (written[0] as JsonObject)["id"].toString().trim('"'))
    }
}
