package app.rondo.client

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.rondo.core.Templates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
        driver.execute(null, "ALTER TABLE settings DROP COLUMN reminder_email", 0)
        driver.execute(null, "DELETE FROM meta WHERE key = 'schema'", 0)
        val deck = Library(first).createDeck("Spanish")

        val store = Store.open(driver)
        // Tags went (1.sqm) and came back (3.sqm), last, as Rondo.sq has them.
        assertEquals("tags", driver.columns("notes").last())
        assertEquals(listOf("reminder_at", "reminder_email"), driver.columns("settings").takeLast(2))
        assertTrue("filter" in driver.columns("smart_decks"))
        assertEquals("4", store.meta("schema"))
        Library(store).newNote(deck.id, Templates.BASIC, mapOf("1" to "hola", "2" to "hello"))
        assertEquals(1, store.q.allNotes().awaitAsList().size)
    }

    @Test
    fun aDatabaseOpensAgainAfterSigningOut() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Store.open(driver).q.wipe()
        assertEquals("4", Store.open(driver).meta("schema"))
    }
}
