package app.rondo.client

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.rondo.core.Templates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        // As the first version left it: notes with tags, settings without reminders, and no schema version recorded.
        driver.execute(null, "ALTER TABLE notes ADD COLUMN tags TEXT NOT NULL DEFAULT ''", 0)
        driver.execute(null, "ALTER TABLE settings DROP COLUMN reminder_at", 0)
        driver.execute(null, "ALTER TABLE settings DROP COLUMN reminder_email", 0)
        driver.execute(null, "DELETE FROM meta WHERE key = 'schema'", 0)
        val deck = Library(first).createDeck("Spanish")

        val store = Store.open(driver)
        assertFalse("tags" in driver.columns("notes"))
        assertEquals(listOf("reminder_at", "reminder_email"), driver.columns("settings").takeLast(2))
        assertEquals("3", store.meta("schema"))
        Library(store).newNote(deck.id, Templates.BASIC, mapOf("1" to "hola", "2" to "hello"))
        assertEquals(1, store.q.allNotes().awaitAsList().size)
    }

    @Test
    fun aDatabaseOpensAgainAfterSigningOut() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Store.open(driver).q.wipe()
        assertEquals("3", Store.open(driver).meta("schema"))
    }
}
