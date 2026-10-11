package app.rondo.sync

import app.rondo.core.Deck
import app.rondo.core.ErrorCode
import app.rondo.core.Filter
import app.rondo.core.Hlc
import app.rondo.core.Note
import app.rondo.core.SmartDeck
import app.rondo.core.Template
import app.rondo.core.record.DeckRecord
import app.rondo.core.record.NoteRecord
import app.rondo.core.record.Record
import app.rondo.core.record.SmartDeckRecord
import app.rondo.core.sync.Page
import app.rondo.core.sync.Pull
import app.rondo.core.sync.Pulled
import app.rondo.core.sync.Push
import app.rondo.core.sync.Pushed
import app.rondo.core.sync.Stream
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** One pool for the whole run: JUnit makes a Harness per test, and a pool each would hold its
 * connections until the run ends, past Postgres's limit. Tests run one at a time. */
private val shared by lazy { Postgres(System.getenv("RONDO_TEST_DATABASE_URL")) }

/** A clean schema from server/db/migrations, two people and helpers to push and pull as them. */
open class Harness {
    val postgres = shared
    val sync = SyncService(postgres)
    val alice = id()
    val bob = id()
    private val clock = Hlc(1)

    fun id() = Uuid.generateV7().toString()

    fun v() = clock.next()

    init {
        postgres.transaction { sql ->
            sql.exec("DROP SCHEMA public CASCADE")
            sql.exec("CREATE SCHEMA public")
            val up = File(System.getProperty("migrations")).listFiles()!!.sorted().joinToString("\n") {
                it.readText().substringAfter("-- +goose Up").substringBefore("-- +goose Down")
            }
            sql.script(up)
            sql.exec("CREATE TABLE goose_db_version (version_id bigint)")
            sql.exec("INSERT INTO goose_db_version VALUES (1)")
        }
    }

    fun user(id: String, pro: Boolean = true) = postgres.transaction { sql ->
        sql.exec(
            "INSERT INTO users (id, name, username, pro_until) " +
                "VALUES (?::uuid, 'Someone', ?, ${if (pro) "now() + interval '1 day'" else "NULL"})",
            id,
            "u$id",
        )
    }

    fun lend(deck: String, to: String, role: Int) = postgres.transaction { sql ->
        sql.exec("INSERT INTO shares (deck_id, user_id, role) VALUES (?::uuid, ?::uuid, ?)", deck, to, role)
    }

    fun deck(owner: String?, parent: String? = null, name: String = "Deck") = Deck(name, "V", v(), owner, parent)

    fun note(deck: Deck, text: String = "front") = Note(
        id(),
        deck.id,
        Template.BASIC.id,
        mapOf(
            "1" to text,
            "2" to "back",
        ),
        v(),
        deck.ownerId,
    )

    fun push(who: String, vararg records: Record): Pushed = sync.push(who, Push(records.toList()))

    fun push(
        who: String,
        decks: List<Deck> = emptyList(),
        notes: List<Note> = emptyList(),
        smart: List<SmartDeck> = emptyList(),
    ) = push(who, *(decks.map(::DeckRecord) + notes.map(::NoteRecord) + smart.map(::SmartDeckRecord)).toTypedArray())

    fun pull(who: String, vararg streams: Stream) = sync.pull(who, Pull(streams.toList()))

    fun Pulled.stream(key: String): Page = streams.first { it.key == key }

    fun Page.decks() = records.filterIsInstance<DeckRecord>().map { it.deck() }

    fun Page.notes() = records.filterIsInstance<NoteRecord>().map { it.note() }

    fun Page.smartDecks() = records.filterIsInstance<SmartDeckRecord>().map { it.smartDeck() }

    fun Pushed.code() = rejected.single().code
}

class SyncTest : Harness() {
    @BeforeTest
    fun people() {
        user(alice)
        user(bob)
    }

    @Test
    fun pushedRowsComeBackAndIdlePullsAreFree() {
        val d = deck(alice)
        val n = note(d)
        assertEquals(2, push(alice, decks = listOf(d), notes = listOf(n)).accepted)
        val first = pull(alice).stream("me")
        assertEquals(listOf(n.id), first.notes().map { it.id })
        assertEquals(listOf(d.id), first.deckIds)
        val again = pull(alice, Stream("me", first.cursor, first.fingerprint)).stream("me")
        assertTrue(again.records.isEmpty())
        assertEquals(first.cursor, again.cursor)
        assertEquals(0, push(alice, notes = listOf(n.copy(fields = mapOf("1" to "stale"), v = n.v - 1))).accepted)
    }

    @Test
    fun editorsWriteIntoTheOwnersPileAndViewersCannot() {
        val course = deck(alice)
        push(alice, decks = listOf(course))
        lend(course.id, bob, 2)
        val lent = pull(bob).stream(course.id)
        assertEquals(listOf(course.id), lent.decks().map { it.id })
        val bobs = note(course)
        assertEquals(1, push(bob, notes = listOf(bobs)).accepted)
        assertEquals(listOf(bobs.id), pull(alice).stream("me").notes().map { it.id })
        val wrongOwner = note(course).copy(ownerId = bob)
        assertEquals(ErrorCode.OWNER_MISMATCH.value, push(bob, notes = listOf(wrongOwner)).code())

        postgres.transaction { sql -> sql.exec("UPDATE users SET pro_until = NULL WHERE id = ?::uuid", alice) }
        assertEquals(ErrorCode.FORBIDDEN.value, push(bob, notes = listOf(note(course))).code())
    }

    @Test
    fun deletingALentDeckLeavesBorrowersAnAuthorlessCopy() {
        val course = deck(alice)
        val lesson = deck(alice, course.id)
        val n = note(lesson)
        push(alice, decks = listOf(course, lesson), notes = listOf(n))
        lend(course.id, bob, 1)
        val before = pull(bob).stream(course.id)
        val mine = pull(alice).stream("me")

        push(alice, decks = listOf(course.copy(deletedAt = 1, v = v())))
        val lends = pull(bob, Stream(course.id, before.cursor, before.fingerprint)).lends
        assertEquals(listOf(null), lends.map { it.ownerId })
        val after = pull(alice, Stream("me", mine.cursor, mine.fingerprint)).stream("me")
        assertEquals(emptyList(), after.deckIds)
        assertEquals(
            ErrorCode.READ_ONLY.value,
            push(alice, notes = listOf(n.copy(fields = mapOf("1" to "edit"), v = v()))).code(),
        )

        Maintenance(postgres).run(System.currentTimeMillis())
        assertEquals(
            2,
            postgres.transaction { sql ->
                sql.query("SELECT count(*) FROM decks") { it.getInt(1) }.single()
            },
        )
        postgres.transaction { sql -> sql.exec("DELETE FROM shares") }
        Maintenance(postgres).run(System.currentTimeMillis())
        assertEquals(
            0,
            postgres.transaction { sql ->
                sql.query("SELECT count(*) FROM decks") { it.getInt(1) }.single()
            },
        )
    }

    @Test
    fun purgedDeletionsSendLaggingDevicesBackToTheStart() {
        val d = deck(alice)
        push(alice, decks = listOf(d))
        val old = pull(alice).stream("me")
        push(alice, decks = listOf(d.copy(deletedAt = 1, v = v())))
        Maintenance(postgres).run(System.currentTimeMillis())
        assertTrue(pull(alice, Stream("me", old.cursor, old.fingerprint)).stream("me").resync)
    }

    @Test
    fun movesThatWouldMakeACycleAreRefused() {
        val a = deck(alice)
        val b = deck(alice, a.id)
        push(alice, decks = listOf(a, b))
        assertEquals(ErrorCode.CYCLE.value, push(alice, decks = listOf(a.copy(parentId = b.id, v = v()))).code())
    }

    @Test
    fun tagsSyncAndAnAppThatPredatesThemKeepsThem() {
        val course = deck(alice)
        val n = note(course).copy(tags = "Cardio::Valves pathoma")
        push(alice, decks = listOf(course), notes = listOf(n))
        assertEquals("Cardio::Valves pathoma", pull(alice).stream("me").notes().single().tags)
        // An older app sends the note without tags: its edit lands, the tags stay.
        val untagged =
            NoteRecord(n.id, n.ownerId, n.deckId, n.templateId, mapOf("1" to "edited", "2" to "back"), v(), null, null)
        push(alice, untagged)
        val kept = pull(alice).stream("me").notes().single()
        assertEquals("edited" to "Cardio::Valves pathoma", kept.fields["1"] to kept.tags)
        assertEquals(ErrorCode.INVALID.value, push(alice, notes = listOf(n.copy(tags = "a::", v = v()))).code())
    }

    @Test
    fun smartDecksAreTheirOwnersAlone() {
        val course = deck(alice)
        push(alice, decks = listOf(course))
        lend(course.id, bob, 2)
        val picks = Filter(deckIds = listOf(course.id), tags = listOf("Cardio"))
        val smart = SmartDeck("Cardio", picks, "V", v(), alice)
        assertEquals(1, push(alice, smart = listOf(smart)).accepted)
        assertEquals(listOf(smart), pull(alice).stream("me").smartDecks())
        // Never lent with the deck it picks from, and nobody else changes it.
        assertTrue(pull(bob).stream(course.id).smartDecks().isEmpty())
        val taken = smart.copy(name = "Mine now", v = v())
        assertEquals(ErrorCode.FORBIDDEN.value, push(bob, smart = listOf(taken)).code())

        push(alice, smart = listOf(smart.copy(deletedAt = 1, v = v())))
        Maintenance(postgres).run(System.currentTimeMillis())
        assertEquals(
            0,
            postgres.transaction { sql ->
                sql.query("SELECT count(*) FROM smart_decks") { it.getInt(1) }.single()
            },
        )
    }

    @Test
    fun clocksFarAheadAreRefusedWhole() {
        val far = Hlc(3) { System.currentTimeMillis() + 3_600_000 }.next()
        val problem = runCatching {
            push(alice, decks = listOf(deck(alice).copy(v = far)))
        }.exceptionOrNull() as Problem
        assertEquals(ErrorCode.CLOCK_AHEAD, problem.code)
    }
}
