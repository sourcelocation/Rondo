package app.rondo.client

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.async.coroutines.awaitAsList
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
import app.rondo.client.db.Settings as SettingsRow
import app.rondo.client.db.Templates as TemplateRow
import app.rondo.core.CardState
import app.rondo.core.Fsrs
import app.rondo.core.Hlc
import app.rondo.core.Ids
import app.rondo.core.Learning
import app.rondo.core.Memory
import app.rondo.core.Templates
import app.rondo.core.Tree
import app.rondo.core.model.Deck
import app.rondo.core.model.Event
import app.rondo.core.model.Note
import app.rondo.core.model.Settings
import app.rondo.core.model.Template
import app.rondo.core.model.TemplateDef
import app.rondo.core.searchText
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/** What each platform provides. */
interface Platform {
    /** The origin `/api`, `/sync` and `/media` are served from. */
    val apiUrl: String
    val kratosUrl: String

    suspend fun driver(): SqlDriver

    /** Small values kept outside the database: the session token. */
    fun read(key: String): String?

    fun write(key: String, value: String?)

    /** The files in a zip archive, by name: Anki packages. */
    suspend fun unzip(bytes: ByteArray): Map<String, ByteArray>

    fun unzstd(bytes: ByteArray): ByteArray

    /** A SQLite database held in [bytes], to read from: an Anki collection. */
    suspend fun openSqlite(bytes: ByteArray): SqlDriver

    /** Runs a WebAuthn ceremony with Kratos's [options] JSON and returns the credential as JSON. */
    suspend fun passkey(options: String, register: Boolean): String

    /** Reminders the device schedules itself; null where it can't (browsers, the JVM). */
    val notifications: LocalNotifications? get() = null

    /** Pushes from the server; null where there are none. */
    val push: PushChannel? get() = null
}

private val uuid = object : ColumnAdapter<String, ByteArray> {
    override fun decode(databaseValue: ByteArray) = Uuid.fromByteArray(databaseValue).toString()
    override fun encode(value: String) = Uuid.parse(value).toByteArray()
}

val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** The device's database: rows as the API's models, edits marked for pushing, card states kept folded. */
class Store private constructor(val driver: SqlDriver) {
    val db = Database(
        driver,
        Card_state.Adapter(uuid, uuid),
        Decks.Adapter(uuid, uuid, uuid),
        Events.Adapter(uuid, uuid),
        Lends.Adapter(uuid, uuid),
        Notes.Adapter(uuid, uuid, uuid, uuid),
        Rejects.Adapter(uuid),
        TemplateRow.Adapter(uuid, uuid),
    )
    val q = db.rondoQueries
    private val revision = MutableStateFlow(0)

    /** Bumps after every write that screens show: open screens reload on it. */
    val changes: StateFlow<Int> get() = revision

    init {
        val tables = arrayOf("decks", "templates", "notes", "events", "settings", "card_state", "lends", "rejects")
        driver.addListener(*tables, listener = { revision.value++ })
    }

    /** The account's id, or before signing in a placeholder that signing in replaces. */
    lateinit var me: String
        private set
    lateinit var hlc: Hlc
        private set

    companion object {
        suspend fun open(driver: SqlDriver): Store {
            val schema = Database.Schema.version
            val fresh = driver.all("SELECT 1 FROM sqlite_master WHERE name = 'meta'") { true }.isEmpty()
            if (fresh) Database.Schema.awaitCreate(driver)
            return Store(driver).apply {
                // Databases made by older versions move forward through the migrations (db/*.sqm).
                val at = if (fresh) schema else meta("schema")?.toLong() ?: 1
                if (at < schema) Database.Schema.awaitMigrate(driver, at, schema)
                if (at < schema || fresh) putMeta("schema", "$schema")
                me = meta("account") ?: Ids.new().also { putMeta("account", it) }
                // The device's part of every clock, picked once.
                val device = meta("device")?.toInt() ?: (0..Hlc.DEVICE_MASK).random().also { putMeta("device", "$it") }
                hlc = Hlc(device)
                meta("clock")?.toLong()?.let(hlc::observe)
            }
        }
    }

    suspend fun meta(key: String): String? = q.meta(key).awaitAsOneOrNull()

    suspend fun putMeta(key: String, value: String) {
        q.putMeta(key, value)
    }

    suspend fun clock(): Long = hlc.next().also { putMeta("clock", it.toString()) }

    /** Rows and events made here that haven't reached the server yet. */
    suspend fun pending(): Int = q.pending().awaitAsOne().toInt()

    internal fun becomes(account: String) {
        me = account
    }

    // Reading ------------------------------------------------------------------------------------

    suspend fun decks(): List<Deck> = q.decks().awaitAsList().map { it.model() }

    suspend fun tree(): Tree = Tree(decks())

    suspend fun templates(): Map<String, Template> =
        Templates.builtins.associateBy { it.id } + q.templates().awaitAsList().map { it.model() }.associateBy { it.id }

    suspend fun template(id: String): Template? = Templates.builtin(id) ?: q.template(id).awaitAsOneOrNull()?.model()

    suspend fun note(id: String): Note? = q.note(id).awaitAsOneOrNull()?.model()

    suspend fun lends(): Map<String, Int> = q.lends().awaitAsList().associate { it.deck_id to it.role.toInt() }

    suspend fun settings(): Settings = q.settings().awaitAsOneOrNull()?.let {
        Settings(
            me, it.grading.toInt(), it.theme.toInt(), it.text_size.toInt(), it.v, it.timezone, it.fsrs_weights,
            it.reminder_at?.toInt(), it.reminder_email,
        )
    } ?: Settings(me, 4, 0, 100, 0)

    suspend fun fsrs(retention: Int = 90): Fsrs = Fsrs(Fsrs.parse(settings().fsrsWeights), retention / 100.0)

    // Writing: local edits are dirty until the server has them -----------------------------------

    suspend fun save(d: Deck, dirty: Boolean = true) = q.putDeck(d.row(dirty))

    suspend fun save(t: Template, dirty: Boolean = true) = q.putTemplate(t.row(dirty))

    suspend fun save(n: Note, template: Template, dirty: Boolean = true) {
        val fields = json.encodeToString(n.fields)
        val search = searchText(n.fields, Templates.textFields(template))
        q.putNote(Notes(n.id, n.ownerId, n.deckId, n.templateId, fields, search, n.v, n.deletedAt, dirty))
    }

    suspend fun save(e: Event, pushed: Boolean = false) = q.putEvent(
        Events(
            e.id, e.subjectId, e.card.toLong(), e.kind.toLong(), e.value?.toLong(), e.at, e.durationMs?.toLong(), e.due,
            e.stability, e.difficulty, e.reps?.toLong(), e.lapses?.toLong(), pushed,
        ),
    )

    suspend fun save(s: Settings, dirty: Boolean = true) {
        val (grading, theme, size) = listOf(s.grading, s.theme, s.textSize).map { it.toLong() }
        q.putSettings(
            SettingsRow(
                1, grading, theme, size, s.timezone, s.fsrsWeights, s.v, dirty, s.reminderAt?.toLong(), s.reminderEmail,
            ),
        )
    }

    /** Re-folds the cards of [notes] from their events, so lists and sessions read ready states. */
    suspend fun refreshCards(notes: Collection<Note>, fsrs: Fsrs? = null) {
        if (notes.isEmpty()) return
        val scheduler = fsrs ?: fsrs()
        val templates = templates()
        val events = notes.map { it.id }.chunked(500).flatMap { q.eventsIn(it).awaitAsList() }
            .map { it.model(me) }.groupBy { it.subjectId }
        db.transaction {
            for (n in notes) {
                val template = templates[n.templateId]
                if (n.deletedAt != null || template == null) {
                    q.dropCards(listOf(n.id))
                    continue
                }
                val cards = Templates.cards(template, n)
                val byCard = events[n.id].orEmpty().groupBy { it.card }
                for (card in cards) {
                    q.putCard(Learning.fold("${n.id}:$card", byCard[card].orEmpty(), scheduler).row(n, card))
                }
                q.dropCardsOf(n.id, cards.map { it.toLong() })
            }
        }
    }

    /** Every card state, folded again: after new FSRS weights or a fresh copy of the data. */
    suspend fun refoldAll() {
        refreshCards(q.allNotes().awaitAsList().map { it.model() })
    }
}

fun Decks.model() = Deck(
    id, name, position, color.toInt(), new_per_day.toInt(), reviews_per_day.toInt(), retention.toInt(), gated,
    unlock_at.toInt(), v, owner_id, parent_id, icon, description, language, deleted_at,
)

fun Deck.row(dirty: Boolean) = Decks(
    id, ownerId, parentId, name, position, icon, color.toLong(), description, language, newPerDay.toLong(),
    reviewsPerDay.toLong(), retention.toLong(), gated, unlockAt.toLong(), v, deletedAt, dirty,
)

fun TemplateRow.model() =
    Template(id, name, kind.toInt(), json.decodeFromString<TemplateDef>(def), v, owner_id, deleted_at)

fun Template.row(dirty: Boolean) =
    TemplateRow(id, ownerId, name, kind.toLong(), json.encodeToString(def), v, deletedAt, dirty)

fun Notes.model() =
    Note(id, deck_id, template_id, json.decodeFromString<Map<String, String>>(fields), v, owner_id, deleted_at)

fun Events.model(me: String) = Event(
    id, me, subject_id, card.toInt(), kind.toInt(), at, value_?.toInt(), duration_ms?.toInt(), due, stability,
    difficulty, reps?.toInt(), lapses?.toInt(),
)

fun Card_state.model(): CardState {
    val memory = Memory(state.toInt(), step.toInt(), stability, difficulty, due, last_at, reps.toInt(), lapses.toInt())
    return CardState(memory, learned_at, introduced_at, suspended, buried_at, flag.toInt())
}

fun CardState.row(n: Note, card: Int) = Card_state(
    n.id, card.toLong(), n.deckId, memory.state.toLong(), memory.step.toLong(), memory.stability,
    memory.difficulty, memory.due, memory.last, memory.reps.toLong(), memory.lapses.toLong(),
    learnedAt, introducedAt, suspended, buriedAt, flag.toLong(),
)

/** Every row of a raw query: an imported collection's, or the schema check before SQLDelight's. */
suspend fun <T> SqlDriver.all(sql: String, row: (SqlCursor) -> T): List<T> =
    executeQuery(null, sql, { c -> QueryResult.Value(buildList { while (c.next().value) add(row(c)) }) }, 0).await()
