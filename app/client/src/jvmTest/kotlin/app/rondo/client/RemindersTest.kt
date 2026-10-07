package app.rondo.client

import app.rondo.core.Ids
import app.rondo.core.Templates
import app.rondo.core.model.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking

class RemindersTest {
    /** A device that schedules its own reminders, remembering the last schedule. */
    private class Phone(platform: Platform) : Platform by platform {
        var scheduled: List<LocalReminder> = emptyList()
        override val notifications = object : LocalNotifications {
            override suspend fun allowed(ask: Boolean) = true

            override suspend fun schedule(reminders: List<LocalReminder>) {
                scheduled = reminders
            }
        }
    }

    private val now = Instant.parse("2026-10-07T12:00:00Z").toEpochMilliseconds()
    private val day = 24 * 3_600_000L

    @Test
    fun forecastsAndSchedulesAWeek() = runBlocking {
        val phone = Phone(JvmPlatform("http://localhost", "http://localhost", ":memory:", null))
        val rondo = Rondo.open(phone, autoSync = false)
        val store = rondo.store
        store.save(store.settings().copy(timezone = "UTC", reminderAt = 18 * 60, v = store.clock()))
        val library = rondo.library
        val deck = library.createDeck("Spanish")
        library.updateDeck(deck.id) { it.copy(newPerDay = 10, reviewsPerDay = 100) }
        val notes = List(15) { library.newNote(deck.id, Templates.BASIC, mapOf("1" to "uno $it", "2" to "one")) }
        // Three reviews: one overdue, one due tomorrow morning, one in three days.
        for ((note, due) in notes.take(3).zip(listOf(now - day, now + day - 2 * 3_600_000L, now + 3 * day))) {
            store.driver.execute(null, "UPDATE card_state SET state = 2, due = $due WHERE note_id = ?", 1) {
                bindBytes(0, kotlin.uuid.Uuid.parse(note.id).toByteArray())
            }.await()
        }

        // New cards up to the deck's limit, and the reviews due by each day's end.
        assertEquals(listOf(11, 12, 12, 13, 13, 13, 13), rondo.reminders.forecast(now))

        phone.write("reminders.local", "1")
        rondo.reminders.schedule(now = now)
        assertEquals(7, phone.scheduled.size)
        assertEquals(Instant.parse("2026-10-07T18:00:00Z").toEpochMilliseconds(), phone.scheduled.first().at)
        assertEquals(strings.reminderTitle(11), phone.scheduled.first().title)

        // Studied today: today's reminder goes, the rest stay.
        store.save(Event(Ids.new(), store.me, notes[3].id, 0, 1, now - 3_600_000L, value = 3))
        rondo.reminders.schedule(now = now)
        assertEquals(6, phone.scheduled.size)
        assertEquals(Instant.parse("2026-10-08T18:00:00Z").toEpochMilliseconds(), phone.scheduled.first().at)

        // Reminders off: nothing scheduled.
        store.save(store.settings().copy(reminderAt = null, v = store.clock()))
        rondo.reminders.schedule(now = now)
        assertEquals(0, phone.scheduled.size)
    }
}
