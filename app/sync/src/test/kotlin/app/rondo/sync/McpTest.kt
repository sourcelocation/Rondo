package app.rondo.sync

import app.rondo.core.notes.domain.Template
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An agent signed in through Hydra drives Rondo's tools over hosted MCP. */
class McpTest : Harness() {
    @BeforeTest
    fun people() {
        user(alice)
        user(bob, pro = false)
    }

    private fun call(tool: String, args: String) =
        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$args}}"""

    /** Hosted MCP with any bearer token taken as that person, and [block] calling it. */
    private fun withMcp(block: suspend Agent.() -> Unit) = testApplication {
        val auth = object : Auth("", null, "") {
            override suspend fun agent(token: String) = token
        }
        application { SyncServer(postgres, sync, auth, "http://localhost", "http://hydra/").install(this) }
        Agent(client).block()
    }

    private inner class Agent(private val client: HttpClient) {
        suspend fun post(token: String?, body: String): HttpResponse = client.post("/mcp") {
            token?.let { header("Authorization", "Bearer $it") }
            header("Host", "localhost")
            header("Accept", "application/json, text/event-stream")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        /** What [tool] says to alice's agent. */
        suspend fun tool(tool: String, args: String = "{}"): String {
            val body = post(alice, call(tool, args)).bodyAsText()
            val json = body.lines().firstOrNull { it.startsWith("data:") }?.removePrefix("data:") ?: body
            val result = Json.parseToJsonElement(json).jsonObject.getValue("result").jsonObject
            return result.getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content
        }
    }

    @Test
    fun agentsCreateDecksAndNotesAsTheirPerson() = withMcp {
        val anonymous = post(null, call("list_decks", "{}"))
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        assertTrue(anonymous.headers["WWW-Authenticate"]!!.contains("oauth-protected-resource"))
        // Agents need no Pro.
        assertEquals(HttpStatusCode.OK, post(bob, call("list_decks", "{}")).status)

        val deck = Regex("\"id\":\"([0-9a-f-]+)").find(tool("create_deck", """{"path":"Spanish/Verbs"}"""))!!
            .groupValues[1]
        val added = tool(
            "create_notes",
            """{"deck_id":"$deck","template_id":"${Template.BASIC.id}","rows":[["**hablar**","to speak"]]}""",
        )
        assertEquals("Added 1 note.", added)
        val paths = tool("list_decks")
        assertTrue(paths.contains("Spanish / Verbs"), paths)
        val found = tool("search_notes", """{"text":"hablar"}""")
        assertTrue(found.contains("to speak"), found)
        assertTrue(tool("due_today").contains("\"new\":1"))
    }

    @Test
    fun batchesSkipWhatIsThereAndPage() = withMcp {
        val made = tool("create_deck", """{"path":"Chinese/HSK3"}""")
        val deck = Regex("\"id\":\"([0-9a-f-]+)").find(made)!!.groupValues[1]
        val batch = """{"deck_id":"$deck","template_id":"${Template.BASIC.id}","tags":["HSK3"],""" +
            """"columns":["Front","Back","tags"],"rows":[["差不多","chàbuduō","adv"],["了","le",""],["了","again",""]]}"""
        assertEquals("Added 2 notes. Skipped, already there (1): row 3 (了).", tool("create_notes", batch))
        // Sent again, as after a failure: nothing doubles.
        assertTrue(tool("create_notes", batch).startsWith("Added 0 notes. Skipped, already there (3)"))
        val unknown = tool(
            "create_notes",
            """{"deck_id":"$deck","template_id":"${Template.BASIC.id}","columns":["Word"],"rows":[["x"]]}""",
        )
        assertTrue(unknown.startsWith("No field “Word”"), unknown)

        val first = tool("search_notes", """{"deck_id":"$deck","limit":1,"fields":["Front"]}""")
        assertTrue(first.contains("差不多") && !first.contains("chàbuduō") && first.contains("adv HSK3"), first)
        val next = Regex("\"next\":\"([0-9a-f-]+)").find(first)!!.groupValues[1]
        val second = tool("search_notes", """{"deck_id":"$deck","limit":1,"after":"$next"}""")
        assertTrue(second.contains("了") && !second.contains("\"next\""), second)

        val id = Regex("\"rows\":\\[\\[\"([0-9a-f-]+)").find(second)!!.groupValues[1]
        val missing = "00000000-0000-7000-8000-000000000000"
        val updated =
            tool("update_notes", """{"notes":[{"id":"$id","fields":{"Back":"le (particle)"}},{"id":"$missing"}]}""")
        assertEquals("Updated 1 note. Not found (1): $missing.", updated)
        assertTrue(tool("get_notes", """{"ids":["$id"]}""").contains("le (particle)"))

        assertEquals("Deleted Chinese / HSK3.", tool("delete_deck", """{"id":"$deck"}"""))
        assertFalse(tool("list_decks").contains("HSK3"))
    }

    @Test
    fun csvGoesInAndComesOut() = withMcp {
        val made = tool("create_deck", """{"path":"German"}""")
        val deck = Regex("\"id\":\"([0-9a-f-]+)").find(made)!!.groupValues[1]
        suspend fun notes(csv: String) = tool(
            "create_notes",
            """{"deck_id":"$deck","template_id":"${Template.BASIC.id}","csv":${Json.encodeToString(
                String.serializer(),
                csv,
            )}}""",
        )
        assertEquals(
            "Added 2 notes.",
            notes("Front;Back;Tags\n**der Hund**;the dog;nouns\n\"die Katze; f.\";the cat;nouns"),
        )
        assertTrue(notes("Front,Back\n<b>das Haus</b>,house").startsWith("Its cells are HTML"))

        val exported = Json.parseToJsonElement(tool("export_csv", """{"deck_id":"$deck"}""")).jsonObject
        val basic = exported.getValue("Basic").jsonPrimitive.content
        assertTrue(basic.startsWith("Front,Back,Tags"), basic)
        assertTrue(basic.contains("**der Hund**,the dog,nouns") && basic.contains("die Katze; f.,the cat"), basic)
        // What it gives, it takes back: everything is there already.
        val back = notes(basic)
        assertTrue(back.startsWith("Added 0 notes. Skipped, already there (2)"), back)
    }
}
