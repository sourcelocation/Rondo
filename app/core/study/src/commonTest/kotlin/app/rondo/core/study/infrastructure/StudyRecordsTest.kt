package app.rondo.core.study.infrastructure

import app.rondo.core.kernel.infrastructure.Record
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/** Events', settings' and smart decks' records read and write the JSON every app and the server already store, through the domain and back. */
class StudyRecordsTest {
    private val id = "0190f3e2-6b1a-7c3d-8e4f-123456789abc"
    private val other = "0190f3e2-6b1a-7c3d-8e4f-cba987654321"

    private fun <T> roundTrip(serializer: KSerializer<T>, json: String, through: (T) -> T) {
        val record = Record.json.decodeFromString(serializer, json)
        val again = Record.json.encodeToString(serializer, through(record))
        assertEquals(Record.json.parseToJsonElement(json), Record.json.parseToJsonElement(again), json)
    }

    @Test
    fun settingsAndSmartDecks() {
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
}
