package app.rondo.sync

import app.rondo.core.Templates
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An agent signed in through Hydra drives Rondo's tools over hosted MCP. */
class McpTest : Harness() {
    @BeforeTest
    fun people() {
        user(alice)
        user(bob, pro = false)
    }

    private fun call(tool: String, args: String) =
        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$args}}"""

    @Test
    fun agentsCreateDecksAndNotesAsTheirPerson() = testApplication {
        val auth = object : Auth("", null, "") {
            override suspend fun agent(token: String) = token
        }
        application {
            rondo(sync, auth)
            mcp(db, sync, auth, "http://localhost", "http://hydra/")
        }
        suspend fun mcp(token: String?, body: String) = client.post("/mcp") {
            token?.let { header("Authorization", "Bearer $it") }
            header("Host", "localhost")
            header("Accept", "application/json, text/event-stream")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val anonymous = mcp(null, call("list_decks", "{}"))
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        assertTrue(anonymous.headers["WWW-Authenticate"]!!.contains("oauth-protected-resource"))
        assertEquals(HttpStatusCode.Forbidden, mcp(bob, call("list_decks", "{}")).status)

        val created = mcp(alice, call("create_deck", """{"path":"Spanish/Verbs"}""")).bodyAsText()
        val deck = Regex("\\\\\"id\\\\\":\\\\\"([0-9a-f-]+)").find(created)!!.groupValues[1]
        val notes = mcp(
            alice,
            call(
                "create_notes",
                """{"deck_id":"$deck","template_id":"${Templates.BASIC}",""" +
                    """"notes":[{"fields":{"Front":"**hablar**","Back":"to speak"}}]}""",
            ),
        ).bodyAsText()
        assertTrue(notes.contains("created"), notes)
        val paths = mcp(alice, call("list_decks", "{}")).bodyAsText()
        assertTrue(paths.contains("Spanish / Verbs"), paths)
        val found = mcp(alice, call("search_notes", """{"text":"hablar"}""")).bodyAsText()
        assertTrue(found.contains("to speak"), found)
        val due = mcp(alice, call("due_today", "{}")).bodyAsText()
        assertTrue(due.contains("\\\"new\\\":1"), due)
    }
}
