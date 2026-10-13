package app.rondo.client.storage

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.async.coroutines.awaitCreate
import app.cash.sqldelight.async.coroutines.awaitMigrate
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.rondo.client.db.Card_state
import app.rondo.client.db.Database
import app.rondo.client.db.Decks
import app.rondo.client.db.Events
import app.rondo.client.db.Lends
import app.rondo.client.db.Notes
import app.rondo.client.db.Rejects
import app.rondo.client.db.Smart_decks
import app.rondo.client.db.Templates
import app.rondo.core.Hlc
import app.rondo.core.sql.SqlFilter
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The device's database: opened, moved to the newest schema, and kept on shelves, one for each
 * kind of thing. It knows whose it is ([me]: before signing in, a placeholder that signing in
 * replaces) and keeps the clock every change is stamped with. Only shelves read and write rows.
 */
class Store private constructor(internal val driver: SqlDriver) {
    internal val db = Database(
        driver,
        Card_state.Adapter(UUID, UUID),
        Decks.Adapter(UUID, UUID, UUID),
        Events.Adapter(UUID, UUID),
        Lends.Adapter(UUID, UUID),
        Notes.Adapter(UUID, UUID, UUID, UUID),
        Rejects.Adapter(UUID),
        Smart_decks.Adapter(UUID, UUID),
        Templates.Adapter(UUID, UUID),
    )
    internal val q = db.rondoQueries
    private val revision = MutableStateFlow(0)

    /** Bumps after every write that screens show: open screens reload on it. */
    val changes: StateFlow<Int> get() = revision

    lateinit var me: String
        private set
    lateinit var hlc: Hlc
        private set

    val decks = DeckShelf(this)
    val templates = TemplateShelf(this)
    val notes = NoteShelf(this)
    val events = EventShelf(this)
    val settings = SettingsShelf(this)
    val smartDecks = SmartDeckShelf(this)
    val cards = CardStates(this)
    val media = MediaShelf(this)
    val streams = Streams(this)
    val lends = Lends(this)
    val rejects = Rejects(this)

    /** The shelves sync keeps in step with the server, in the order the server applies what they hold. */
    val synced: List<Shelf> = listOf(decks, templates, notes, events, settings, smartDecks)

    init {
        val tables = arrayOf(
            "decks", "templates", "notes", "events", "settings", "smart_decks", "card_state", "lends", "rejects",
        )
        driver.addListener(*tables, listener = { revision.value++ })
    }

    companion object {
        suspend fun open(driver: SqlDriver): Store {
            val schema = Database.Schema.version
            val fresh = driver.executeQuery(
                null,
                "SELECT 1 FROM sqlite_master WHERE name = 'meta'",
                { c -> QueryResult.Value(c.next().value) },
                0,
            ).await().not()
            if (fresh) Database.Schema.awaitCreate(driver)
            return Store(driver).apply {
                // Databases made by older versions move forward through the migrations (db/*.sqm).
                val at = if (fresh) schema else meta("schema")?.toLong() ?: 1
                if (at < schema) Database.Schema.awaitMigrate(driver, at, schema)
                if (at < schema || fresh) putMeta("schema", "$schema")
                me = meta("account") ?: Uuid.generateV7().toString().also { putMeta("account", it) }
                // The device's part of every clock, picked once.
                val device = meta("device")?.toInt() ?: (0..Hlc.DEVICE_MASK).random().also { putMeta("device", "$it") }
                hlc = Hlc(device)
                meta("clock")?.toLong()?.let(hlc::observe)
            }
        }

        private val UUID = object : ColumnAdapter<String, ByteArray> {
            override fun decode(databaseValue: ByteArray) = Uuid.fromByteArray(databaseValue).toString()
            override fun encode(value: String) = Uuid.parse(value).toByteArray()
        }
    }

    /** A small value kept beside the data: the account, the schema's version, what was last picked. */
    suspend fun meta(key: String): String? = q.meta(key).awaitAsOneOrNull()

    suspend fun putMeta(key: String, value: String) {
        q.putMeta(key, value)
    }

    /** A clock value newer than any this device has made or seen. */
    suspend fun clock(): Long = hlc.next().also { putMeta("clock", it.toString()) }

    /** Changes made here that haven't reached the server yet. */
    suspend fun pending(): Int = q.pending().awaitAsOne().toInt()

    /** Writes [block]'s changes together, or none of them. */
    suspend fun <T> transaction(block: suspend () -> T): T = db.transactionWithResult { block() }

    /**
     * The things made before signing in become [account]'s, so they sync up; signing out empties
     * the device for a new placeholder.
     */
    internal suspend fun becomes(account: String) {
        val placeholder = me
        if (placeholder == account) return
        transaction {
            q.adoptDecks(account, placeholder)
            q.adoptTemplates(account, placeholder)
            q.adoptNotes(account, placeholder)
            q.adoptSmartDecks(account, placeholder)
            putMeta("account", account)
        }
        me = account
        settings.save(settings.get().copy(userId = account, v = clock()))
    }

    /** Removes the account's data from the device; it's a placeholder's again. */
    internal suspend fun wipe() {
        // A query of several statements runs when awaited.
        q.wipe().await()
        me = Uuid.generateV7().toString().also { putMeta("account", it) }
    }

    /** Rows of [sql], with [args] bound as a [SqlFilter]'s are: ids as bytes, numbers as numbers. */
    internal suspend fun <T> select(sql: String, args: List<Any>, row: (Row) -> T): List<T> =
        driver.executeQuery(null, sql, { c ->
            val out = ArrayList<T>()
            val r = Row(c)
            while (c.next().value) out += row(r)
            QueryResult.Value(out)
        }, args.size) {
            args.forEachIndexed { i, a ->
                when (a) {
                    is SqlFilter.Id -> bindBytes(i, Uuid.parse(a.value).toByteArray())
                    is Long -> bindLong(i, a)
                    else -> bindString(i, a.toString())
                }
            }
        }.await()

    /** A row of a [select]: its columns by index. */
    internal class Row(private val c: SqlCursor) {
        fun id(i: Int): String = Uuid.fromByteArray(c.getBytes(i)!!).toString()

        fun long(i: Int): Long = c.getLong(i) ?: 0

        /** A count SQLite's `total()` made: a real number. */
        fun total(i: Int): Int = c.getDouble(i)?.toInt() ?: 0
    }
}
