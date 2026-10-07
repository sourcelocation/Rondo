package app.rondo.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.rondo.client.JvmPlatform
import app.rondo.client.Rondo
import app.rondo.client.model
import app.rondo.core.Learning
import app.rondo.core.Templates
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/** Real clients (in-memory SQLite) syncing through a real sync server and Postgres. */
class ConvergenceTest : Harness() {
    private val server = embeddedServer(CIO, port = 0) {
        rondo(
            sync,
            object : Auth("", null, "") {
                override suspend fun session(token: String) = token
            },
        )
    }.start()
    private val url = runBlocking { "http://localhost:${server.engine.resolvedConnectors().first().port}" }
    private val scope = CoroutineScope(Dispatchers.Default)

    @BeforeTest
    fun people() {
        user(alice)
        user(bob)
    }

    @AfterTest
    fun stop() {
        scope.cancel()
        server.stop(0, 0)
    }

    private suspend fun device(who: String): Rondo =
        Rondo.open(JvmPlatform(url, "", ":memory:", null), scope, autoSync = false).also {
            it.account.useSession(who, who)
        }

    private suspend fun Rondo.notes() = store.q.allNotes().awaitAsList().map { it.model() }

    @Test
    fun devicesOfOnePersonConvergeAndTheNewerEditWins() = runBlocking {
        val phone = device(alice)
        val laptop = device(alice)
        val deck = phone.library.createDeck("Spanish")
        val note = phone.library.newNote(deck.id, Templates.BASIC, mapOf("1" to "hola", "2" to "hello"))
        phone.syncer.sync()
        laptop.syncer.sync()
        assertEquals(listOf("hola"), laptop.notes().map { it.fields["1"] })

        laptop.library.saveNote(note.copy(fields = mapOf("1" to "buenas", "2" to "hello")))
        Thread.sleep(5)
        phone.library.saveNote(note.copy(fields = mapOf("1" to "hola!", "2" to "hello")))
        laptop.syncer.sync()
        phone.syncer.sync()
        laptop.syncer.sync()
        assertEquals(listOf("hola!"), laptop.notes().map { it.fields["1"] })
        assertEquals(listOf("hola!"), phone.notes().map { it.fields["1"] })
    }

    @Test
    fun lendingEditingStudyingAndRevoking() = runBlocking {
        val alices = device(alice)
        val course = alices.library.createDeck("Course")
        alices.library.newNote(course.id, Templates.BASIC, mapOf("1" to "uno", "2" to "one"))
        alices.syncer.sync()
        lend(course.id, bob, 2)

        val bobs = device(bob)
        bobs.syncer.sync()
        assertEquals(listOf(course.id), bobs.store.decks().map { it.id })
        val added = bobs.library.newNote(course.id, Templates.BASIC, mapOf("1" to "dos", "2" to "two"))
        assertEquals(alice, added.ownerId)

        val session = app.rondo.client.Session(bobs.store, bobs.library, course.id).apply { start() }
        session.answer(3, 1000)
        bobs.syncer.sync()
        alices.syncer.sync()
        assertEquals(setOf("uno", "dos"), alices.notes().map { it.fields["1"] }.toSet())
        assertTrue(alices.store.q.reviewsSince(0).awaitAsList().isEmpty(), "Bob's progress stays his")
        assertEquals(1, bobs.store.q.reviewsSince(0).awaitAsList().size)

        db.transaction { c -> c.exec("DELETE FROM shares") }
        bobs.syncer.sync()
        assertTrue(bobs.store.decks().isEmpty())
        assertTrue(bobs.notes().isEmpty())
    }

    @Test
    fun aRemovedLentDeckStaysWithItsBorrowerWithoutAnAuthor() = runBlocking {
        val alices = device(alice)
        val other = device(alice)
        val course = alices.library.createDeck("Course")
        alices.library.newNote(course.id, Templates.BASIC, mapOf("1" to "a", "2" to "b"))
        alices.syncer.sync()
        other.syncer.sync()
        lend(course.id, bob, 1)
        val bobs = device(bob)
        bobs.syncer.sync()

        alices.library.delete(course.id)
        alices.syncer.sync()
        other.syncer.sync()
        bobs.syncer.sync()
        assertTrue(other.store.decks().isEmpty(), "gone from the author's other devices")
        assertNull(bobs.store.q.lends().awaitAsList().single().owner_id)
        assertEquals(1, bobs.notes().size)
        assertEquals(0, alices.store.q.reviewsSince(0).awaitAsList().size)
    }

    @Test
    fun levelsUnlockAndTheUnlockSyncs() = runBlocking {
        val phone = device(alice)
        val hsk = phone.library.createDeck("HSK")
        phone.library.updateDeck(hsk.id) { it.copy(gated = true) }
        val one = phone.library.createDeck("HSK 1", hsk.id)
        val two = phone.library.createDeck("HSK 2", hsk.id)
        phone.library.newNote(one.id, Templates.BASIC, mapOf("1" to "我", "2" to "I"))
        phone.library.newNote(two.id, Templates.BASIC, mapOf("1" to "你", "2" to "you"))
        assertEquals(false, phone.library.overview().levels.getValue(two.id).open)
        val session = app.rondo.client.Session(phone.store, phone.library, hsk.id).apply { start() }
        assertEquals("我", session.current?.note?.fields?.get("1"))
        session.answer(4, 500)
        assertEquals(setOf(two.id), session.opened)
        phone.syncer.sync()
        val laptop = device(alice)
        laptop.syncer.sync()
        assertEquals(true, laptop.library.overview().levels.getValue(two.id).open)
        assertEquals(listOf(two.id), laptop.store.q.unlocked().awaitAsList())
        assertEquals(Learning.UNLOCK, 9)
    }
}
