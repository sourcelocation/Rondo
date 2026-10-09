package app.rondo.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/** Choosing a server of one's own: what an address means, and when it may change. */
class ServerChoiceTest {
    private fun rondo() = runBlocking {
        Rondo.open(
            JvmPlatform("https://rondo.example", "https://rondo.example/auth", ":memory:", null),
            autoSync = false,
        )
    }

    @Test
    fun addressesBecomeOrigins() {
        assertEquals("https://cards.example.org", Net.origin("cards.example.org"))
        assertEquals("https://cards.example.org", Net.origin("https://cards.example.org/app/"))
        assertEquals("http://localhost:23900", Net.origin("http://localhost:23900/app/settings"))
        assertEquals("https://cards.example.org:8443", Net.origin("cards.example.org:8443"))
        assertNull(Net.origin("ftp://cards.example.org"))
    }

    @Test
    fun aServerThatDoesNotAnswerIsNotUsed() = runBlocking {
        val rondo = rondo()
        val e = assertFailsWith<SignInError> { rondo.net.useServer("http://localhost:9") }
        assertEquals("no_server", e.code)
        assertEquals("https://rondo.example", rondo.net.apiUrl)
    }

    @Test
    fun aChosenServerServesEverything() = runBlocking {
        val rondo = rondo()
        rondo.platform.write(Net.SERVER, "https://cards.example.org")
        assertEquals("https://cards.example.org", rondo.net.apiUrl)
        assertEquals("https://cards.example.org/auth", rondo.net.kratosUrl)
        // Empty, or the app's own, goes back to it without asking anyone.
        rondo.net.useServer(" ")
        assertEquals("https://rondo.example", rondo.net.apiUrl)
        assertEquals("https://rondo.example/auth", rondo.net.kratosUrl)
        assertNull(rondo.net.chosen)
    }

    @Test
    fun onlyWhileSignedOut() = runBlocking {
        val rondo = rondo()
        rondo.platform.write(Account.TOKEN, "a session")
        assertFailsWith<IllegalStateException> { rondo.net.useServer("") }
        Unit
    }
}
