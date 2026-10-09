package app.rondo.sync

import app.rondo.core.Decks
import app.rondo.core.Hlc
import app.rondo.core.Ids
import app.rondo.core.SmartDecks
import app.rondo.core.Templates
import app.rondo.core.model.Deck
import app.rondo.core.model.ErrorCode
import app.rondo.core.model.Filter
import app.rondo.core.model.Note
import app.rondo.core.model.PullRequest
import app.rondo.core.model.PushRequest
import app.rondo.core.model.Rows
import app.rondo.core.model.StreamPage
import app.rondo.core.model.StreamPosition
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One pool for the whole run: JUnit makes a Harness per test, and a pool each would hold its
 * connections until the run ends, past Postgres's limit. Tests run one at a time. */
private val sharedDb by lazy { Db(System.getenv("RONDO_TEST_DATABASE_URL")) }

/** A clean schema from server/db/migrations, two people and helpers to push and pull as them. */
open class Harness {
    val db = sharedDb
    val sync = Sync(db)
    val alice = Ids.new()
    val bob = Ids.new()
    private val clock = Hlc(1)

    fun v() = clock.next()

    init {
        db.transaction { c ->
            c.exec("DROP SCHEMA public CASCADE")
            c.exec("CREATE SCHEMA public")
            val up = File(System.getProperty("migrations")).listFiles()!!.sorted().joinToString("\n") {
                it.readText().substringAfter("-- +goose Up").substringBefore("-- +goose Down")
            }
            c.createStatement().use { it.execute(up) }
            c.exec("CREATE TABLE goose_db_version (version_id bigint)")
            c.exec("INSERT INTO goose_db_version VALUES (1)")
        }
    }

    fun user(id: String, pro: Boolean = true) = db.transaction { c ->
        c.exec(
            "INSERT INTO users (id, name, username, pro_until) " +
                "VALUES (?::uuid, 'Someone', ?, ${if (pro) "now() + interval '1 day'" else "NULL"})",
            id,
            "u$id",
        )
    }

    fun lend(deck: String, to: String, role: Int) = db.transaction { c ->
        c.exec("INSERT INTO shares (deck_id, user_id, role) VALUES (?::uuid, ?::uuid, ?)", deck, to, role)
    }

    fun deck(owner: String?, parent: String? = null, name: String = "Deck") = Decks.new(name, "V", v(), owner, parent)

    fun note(deck: Deck, text: String = "front") = Note(
        Ids.new(),
        deck.id,
        Templates.BASIC,
        mapOf(
            "1" to text,
            "2" to "back",
        ),
        v(),
        deck.ownerId,
    )

    fun push(who: String, rows: Rows) = sync.push(who, PushRequest(rows))

    fun pull(who: String, vararg positions: StreamPosition) = sync.pull(who, PullRequest(positions.toList()))

    fun PullStreams.stream(key: String): StreamPage = streams.first { it.key == key }
}

typealias PullStreams = app.rondo.core.model.PullResponse

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
        assertEquals(2, push(alice, Rows(decks = listOf(d), notes = listOf(n))).accepted)
        val first = pull(alice).stream("me")
        assertEquals(listOf(n.id), first.rows.notes!!.map { it.id })
        assertEquals(listOf(d.id), first.deckIds)
        val again = pull(alice, StreamPosition("me", first.cursor, first.fingerprint)).stream("me")
        assertNull(again.rows.notes)
        assertEquals(first.cursor, again.cursor)
        assertEquals(0, push(alice, Rows(notes = listOf(n.copy(fields = mapOf("1" to "stale"), v = n.v - 1)))).accepted)
    }

    @Test
    fun editorsWriteIntoTheOwnersPileAndViewersCannot() {
        val course = deck(alice)
        push(alice, Rows(decks = listOf(course)))
        lend(course.id, bob, 2)
        val lent = pull(bob).stream(course.id)
        assertEquals(listOf(course.id), lent.rows.decks!!.map { it.id })
        val bobs = note(course)
        assertEquals(1, push(bob, Rows(notes = listOf(bobs))).accepted)
        assertEquals(listOf(bobs.id), pull(alice).stream("me").rows.notes!!.map { it.id })
        val wrongOwner = note(course).copy(ownerId = bob)
        assertEquals(ErrorCode.OWNER_MISMATCH, push(bob, Rows(notes = listOf(wrongOwner))).rejected.single().code)

        db.transaction { c -> c.exec("UPDATE users SET pro_until = NULL WHERE id = ?::uuid", alice) }
        assertEquals(ErrorCode.FORBIDDEN, push(bob, Rows(notes = listOf(note(course)))).rejected.single().code)
    }

    @Test
    fun deletingALentDeckLeavesBorrowersAnAuthorlessCopy() {
        val course = deck(alice)
        val lesson = deck(alice, course.id)
        val n = note(lesson)
        push(alice, Rows(decks = listOf(course, lesson), notes = listOf(n)))
        lend(course.id, bob, 1)
        val before = pull(bob).stream(course.id)
        val mine = pull(alice).stream("me")

        push(alice, Rows(decks = listOf(course.copy(deletedAt = 1, v = v()))))
        val lends = pull(bob, StreamPosition(course.id, before.cursor, before.fingerprint)).lends
        assertEquals(listOf(null), lends.map { it.ownerId })
        val after = pull(alice, StreamPosition("me", mine.cursor, mine.fingerprint)).stream("me")
        assertEquals(emptyList(), after.deckIds)
        assertEquals(
            ErrorCode.READ_ONLY,
            push(alice, Rows(notes = listOf(n.copy(fields = mapOf("1" to "edit"), v = v())))).rejected.single().code,
        )

        Maintenance(db).run(System.currentTimeMillis())
        assertEquals(2, db.transaction { c -> c.query("SELECT count(*) FROM decks") { it.getInt(1) }.single() })
        db.transaction { c -> c.exec("DELETE FROM shares") }
        Maintenance(db).run(System.currentTimeMillis())
        assertEquals(0, db.transaction { c -> c.query("SELECT count(*) FROM decks") { it.getInt(1) }.single() })
    }

    @Test
    fun purgedDeletionsSendLaggingDevicesBackToTheStart() {
        val d = deck(alice)
        push(alice, Rows(decks = listOf(d)))
        val old = pull(alice).stream("me")
        push(alice, Rows(decks = listOf(d.copy(deletedAt = 1, v = v()))))
        Maintenance(db).run(System.currentTimeMillis())
        assertTrue(pull(alice, StreamPosition("me", old.cursor, old.fingerprint)).stream("me").resync)
    }

    @Test
    fun movesThatWouldMakeACycleAreRefused() {
        val a = deck(alice)
        val b = deck(alice, a.id)
        push(alice, Rows(decks = listOf(a, b)))
        assertEquals(
            ErrorCode.CYCLE,
            push(alice, Rows(decks = listOf(a.copy(parentId = b.id, v = v())))).rejected.single().code,
        )
    }

    @Test
    fun tagsSyncAndAnAppThatPredatesThemKeepsThem() {
        val course = deck(alice)
        val n = note(course).copy(tags = "Cardio::Valves pathoma")
        push(alice, Rows(decks = listOf(course), notes = listOf(n)))
        assertEquals("Cardio::Valves pathoma", pull(alice).stream("me").rows.notes!!.single().tags)
        // An older app sends the note without tags: its edit lands, the tags stay.
        push(alice, Rows(notes = listOf(n.copy(fields = mapOf("1" to "edited", "2" to "back"), tags = null, v = v()))))
        val kept = pull(alice).stream("me").rows.notes!!.single()
        assertEquals("edited" to "Cardio::Valves pathoma", kept.fields["1"] to kept.tags)
        assertEquals(
            ErrorCode.INVALID,
            push(alice, Rows(notes = listOf(n.copy(tags = "a::", v = v())))).rejected.single().code,
        )
    }

    @Test
    fun smartDecksAreTheirOwnersAlone() {
        val course = deck(alice)
        push(alice, Rows(decks = listOf(course)))
        lend(course.id, bob, 2)
        val picks = Filter(deckIds = listOf(course.id), tags = listOf("Cardio"))
        val smart = SmartDecks.new("Cardio", picks, "V", v(), alice)
        assertEquals(1, push(alice, Rows(smartDecks = listOf(smart))).accepted)
        assertEquals(listOf(smart), pull(alice).stream("me").rows.smartDecks)
        // Never lent with the deck it picks from, and nobody else changes it.
        assertNull(pull(bob).stream(course.id).rows.smartDecks)
        val taken = smart.copy(name = "Mine now", v = v())
        assertEquals(ErrorCode.FORBIDDEN, push(bob, Rows(smartDecks = listOf(taken))).rejected.single().code)

        push(alice, Rows(smartDecks = listOf(smart.copy(deletedAt = 1, v = v()))))
        Maintenance(db).run(System.currentTimeMillis())
        assertEquals(0, db.transaction { c -> c.query("SELECT count(*) FROM smart_decks") { it.getInt(1) }.single() })
    }

    @Test
    fun clocksFarAheadAreRefusedWhole() {
        val far = Hlc(3) { System.currentTimeMillis() + 3_600_000 }.next()
        val problem = runCatching {
            push(alice, Rows(decks = listOf(deck(alice).copy(v = far))))
        }.exceptionOrNull() as Problem
        assertEquals(ErrorCode.CLOCK_AHEAD, problem.code)
    }
}
