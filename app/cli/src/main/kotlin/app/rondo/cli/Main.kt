package app.rondo.cli

import app.rondo.client.JvmPlatform
import app.rondo.client.LocalWorkspace
import app.rondo.client.Rondo
import app.rondo.mcp.rondoServer
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

/**
 * `rondo-mcp sign-in` signs in with an email code in the terminal; `rondo-mcp` serves the MCP tools
 * over stdio (what Claude Desktop, Claude Code and other local agents run).
 */
fun main(args: Array<String>) = runBlocking {
    val home = File(System.getenv("RONDO_HOME") ?: (System.getProperty("user.home") + "/.rondo")).apply { mkdirs() }
    val url = System.getenv("RONDO_URL") ?: "https://rondo.matthewsource.com"
    val kratos = System.getenv("RONDO_AUTH_URL") ?: "$url/auth"
    val rondo = Rondo.open(
        JvmPlatform(url, kratos, File(home, "data.rondo.db").path, File(home, "session.properties")),
        this,
        autoSync = false,
    )
    if (args.firstOrNull() == "sign-in") {
        print("Email: ")
        val flow = rondo.account.sendCode(readln().trim())
        print("Code from the email: ")
        rondo.account.confirm(flow, readln().trim())
        rondo.syncNow()
        println("Signed in. Add `rondo-mcp` to your agent's MCP servers.")
        return@runBlocking
    }
    if (!rondo.account.signedIn) {
        System.err.println("Run `rondo-mcp sign-in` first.")
        return@runBlocking
    }
    rondo.syncNow()
    val server = rondoServer(LocalWorkspace(rondo))
    val done = CompletableDeferred<Unit>()
    server.onClose { done.complete(Unit) }
    server.createSession(StdioServerTransport(System.`in`.asSource().buffered(), System.out.asSink().buffered()))
    done.await()
    rondo.syncNow()
}
