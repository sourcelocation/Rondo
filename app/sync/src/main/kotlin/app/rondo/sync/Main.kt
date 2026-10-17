package app.rondo.sync

import app.rondo.core.kernel.domain.ErrorCode
import app.rondo.core.sync.infrastructure.Fetch
import app.rondo.core.sync.infrastructure.Fetched
import app.rondo.core.sync.infrastructure.Message
import app.rondo.core.sync.infrastructure.Pull
import app.rondo.core.sync.infrastructure.Pulled
import app.rondo.core.sync.infrastructure.Push
import app.rondo.core.sync.infrastructure.Pushed
import app.rondo.mcp.RondoTools
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.sentry.Sentry
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory

fun main() {
    val env = System.getenv()
    env["RONDO_SENTRY_DSN"]?.let { dsn -> Sentry.init { it.dsn = dsn } }
    val postgres = Postgres(env["RONDO_DATABASE_URL"] ?: error("RONDO_DATABASE_URL is required"))
    val version = postgres.transaction { sql ->
        sql.query("SELECT max(version_id) FROM goose_db_version") { it.getLong(1) }.single()
    }
    check(version >= SyncServer.SCHEMA_VERSION) {
        "The database schema is at $version; this server needs ${SyncServer.SCHEMA_VERSION}. Run `rondo migrate`."
    }
    // The one origin Rondo is served from; Hydra's issuer is on it too.
    val origin = env["RONDO_PUBLIC_URL"] ?: "http://localhost:23900"
    val kratos = env["RONDO_KRATOS_PUBLIC_URL"] ?: "http://localhost:23904"
    val auth = Auth(kratos, env["RONDO_HYDRA_ADMIN_URL"], "$origin/mcp")
    val hydra = env["RONDO_HYDRA_PUBLIC_URL"] ?: "$origin/"
    Maintenance(postgres).start()
    val server = SyncServer(postgres, SyncService(postgres), auth, origin, hydra)
    embeddedServer(
        CIO,
        configure = {
            connector { port = env["RONDO_SYNC_PORT"]?.toInt() ?: 23902 }
            // On a signal, requests in flight get up to 20 seconds, within the pod's grace period.
            shutdownGracePeriod = 1_000
            shutdownTimeout = 20_000
        },
    ) { server.install(this) }.start(wait = true)
}

/**
 * The sync server's HTTP: health, sync (`Rondo-Protocol: 1`), and hosted MCP at /mcp, where agents
 * sign in through Hydra (its tokens are checked by introspection) and use :mcp's tools over this
 * person's data in Postgres.
 */
class SyncServer(
    private val postgres: Postgres,
    private val sync: SyncService,
    private val auth: Auth,
    private val origin: String,
    private val hydra: String,
) {
    private val log = LoggerFactory.getLogger("rondo.sync")
    private val actor = AttributeKey<String>("actor")

    fun install(app: Application) {
        app.install(ContentNegotiation) { json(McpJson) }
        app.routing {
            get("/healthz") { call.respondText("ok") }
            post("/sync/pull") {
                call.handle(Pull.serializer(), Pulled.serializer()) { actor, pull -> sync.pull(actor, pull) }
            }
            post("/sync/push") {
                call.handle(Push.serializer(), Pushed.serializer()) { actor, push -> sync.push(actor, push) }
            }
            post("/sync/fetch") {
                call.handle(Fetch.serializer(), Fetched.serializer()) { actor, fetch -> sync.fetch(actor, fetch) }
            }
        }
        mcp(app)
    }

    /** Reads a [request] from an app signed in, answers with [work]'s [answer], and refuses what it can't. */
    private suspend fun <I, O> ApplicationCall.handle(
        request: KSerializer<I>,
        answer: KSerializer<O>,
        work: suspend (String, I) -> O,
    ) {
        try {
            if (this.request.header("Rondo-Protocol") != PROTOCOL) {
                throw Problem(426, ErrorCode.CLIENT_OUTDATED, "Update the app to keep syncing.")
            }
            val token = this.request.header("Authorization")?.removePrefix("Bearer ")?.trim()
            val who = token?.let { auth.session(it) } ?: throw Problem(401, ErrorCode.UNAUTHORIZED, "Sign in again.")
            val body = Message.json.decodeFromString(request, receiveText())
            val result = withContext(Dispatchers.IO) { work(who, body) }
            respondText(Message.json.encodeToString(answer, result), ContentType.Application.Json)
        } catch (p: Problem) {
            respond(HttpStatusCode.fromValue(p.status), ProblemBody(p.code.value, p.message.orEmpty()))
        } catch (e: Exception) {
            log.error("request failed", e)
            Sentry.captureException(e)
            respond(HttpStatusCode.InternalServerError, ProblemBody(ErrorCode.INTERNAL.value, "Something went wrong."))
        }
    }

    private fun mcp(app: Application) {
        val metadata = "$origin/.well-known/oauth-protected-resource"
        app.routing {
            get("/.well-known/oauth-protected-resource") {
                call.respond(
                    buildJsonObject {
                        put("resource", "$origin/mcp")
                        putJsonArray("authorization_servers") { add(hydra) }
                        putJsonArray("bearer_methods_supported") { add("header") }
                    },
                )
            }
        }
        app.intercept(ApplicationCallPipeline.Plugins) {
            if (!call.request.local.uri.startsWith("/mcp")) return@intercept
            val token = call.request.header("Authorization")?.removePrefix("Bearer ")?.trim()
            val who = token?.let { auth.agent(it) }
            if (who == null) {
                call.response.headers.append("WWW-Authenticate", "Bearer resource_metadata=\"$metadata\"")
                call.respond(HttpStatusCode.Unauthorized)
                return@intercept finish()
            }
            call.attributes.put(actor, who)
        }
        app.mcpStatelessStreamableHttp("/mcp", allowedHosts = listOf(URI(origin).host, "localhost", "127.0.0.1")) {
            RondoTools(PostgresWorkspace(call.attributes[actor], postgres, sync)).server
        }
    }

    @Serializable
    private class ProblemBody(val code: String, val message: String)

    companion object {
        const val SCHEMA_VERSION = 1L

        /** The sync protocol this server speaks; apps on an older one are asked to update. */
        const val PROTOCOL = Message.VERSION
    }
}
