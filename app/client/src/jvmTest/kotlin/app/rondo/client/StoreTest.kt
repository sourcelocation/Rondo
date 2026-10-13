package app.rondo.client

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.rondo.client.services.Library
import app.rondo.client.storage.Store
import app.rondo.core.Template
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class StoreTest {
    private fun JdbcSqliteDriver.columns(table: String): List<String> =
        executeQuery(null, "PRAGMA table_info($table)", { c ->
            val names = ArrayList<String>()
            while (c.next().value) names += c.getString(1)!!
            QueryResult.Value(names)
        }, 0).value

    @Test
    fun aDatabaseFromTheFirstVersionMovesForward() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val first = Store.open(driver)
        // As the first version left it: notes with tags (as now), settings without reminders, no smart decks,
        // and no schema version recorded.
        driver.execute(null, "DROP TABLE smart_decks", 0)
        driver.execute(null, "ALTER TABLE settings DROP COLUMN reminder_at", 0)
        driver.execute(null, "DELETE FROM meta WHERE key = 'schema'", 0)
        val deck = Library(first).createDeck("Spanish")

        val store = Store.open(driver)
        // Tags went (1.sqm) and came back (3.sqm), last, as Rondo.sq has them; reminders by email came (2.sqm)
        // and went (4.sqm).
        assertEquals("tags", driver.columns("notes").last())
        assertEquals("reminder_at", driver.columns("settings").last())
        assertTrue("filter" in driver.columns("smart_decks"))
        assertEquals("5", store.meta("schema"))
        Library(store).newNote(deck.id, Template.BASIC.id, mapOf("1" to "hola", "2" to "hello"))
        assertEquals(1, store.notes.ids().size)
    }

    @Test
    fun changesMadeTogetherNeverUndoEachOther() = runBlocking {
        val library = Library(Store.open(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)))
        val deck = library.createDeck("Spanish")
        // Each reads the deck as stored and changes one thing: settings saved as they're made.
        coroutineScope {
            launch(Dispatchers.Default) { library.updateDeck(deck.id) { it.copy(color = 3) } }
            launch(Dispatchers.Default) { library.updateDeck(deck.id) { it.copy(name = "Español") } }
            launch(Dispatchers.Default) { library.updateDeck(deck.id) { it.copy(newPerDay = 5) } }
        }
        val stored = library.tree()[deck.id]!!
        assertEquals(Triple(3, "Español", 5), Triple(stored.color, stored.name, stored.newPerDay))
    }

    @Test
    fun aDatabaseOpensAgainAfterSigningOut() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Store.open(driver).wipe()
        assertEquals("5", Store.open(driver).meta("schema"))
    }

    @Test
    fun signingOutLeavesNothingBehind() = runBlocking {
        val store = Store.open(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val library = Library(store)
        val deck = library.createDeck("Spanish")
        library.newNote(deck.id, Template.BASIC.id, mapOf("1" to "hola", "2" to "hello"))
        store.wipe()
        assertEquals(0, store.decks.all().size)
        assertEquals(0, store.notes.ids().size)
    }
}
