package app.rondo.sync

import app.rondo.core.model.ErrorCode
import app.rondo.mcp.rondoServer
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.request.receive
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("rondo.sync")

@Serializable
private class ProblemBody(val code: ErrorCode, val message: String)

fun main() {
    val env = System.getenv()
    env["RONDO_SENTRY_DSN"]?.let { dsn -> Sentry.init { it.dsn = dsn } }
    val db = Db(jdbcUrl(env["RONDO_DATABASE_URL"] ?: error("RONDO_DATABASE_URL is required")))
    val version = db.transaction { c ->
        c.query("SELECT max(version_id) FROM goose_db_version") { it.getLong(1) }.single()
    }
    check(version >= SCHEMA_VERSION) {
        "The database schema is at $version; this server needs $SCHEMA_VERSION. Run `rondo migrate`."
    }
    // The one origin Rondo is served from; Hydra's issuer is on it too.
    val origin = env["RONDO_PUBLIC_URL"] ?: "http://localhost:23900"
    val kratos = env["RONDO_KRATOS_PUBLIC_URL"] ?: "http://localhost:23904"
    val auth = Auth(kratos, env["RONDO_HYDRA_ADMIN_URL"], "$origin/mcp")
    val hydra = env["RONDO_HYDRA_PUBLIC_URL"] ?: "$origin/"
    Maintenance(db).start()
    val sync = Sync(db)
    embeddedServer(CIO, port = env["RONDO_SYNC_PORT"]?.toInt() ?: 23902) {
        rondo(sync, auth)
        mcp(db, sync, auth, origin, hydra)
    }.start(wait = true)
}

const val SCHEMA_VERSION = 10L

/** Accepts Go's `postgres://user:pass@host/db` URLs as well as JDBC ones. */
fun jdbcUrl(url: String): String {
    if (url.startsWith("jdbc:")) return url
    val u = URI(url)
    val (user, password) = (u.userInfo ?: ":").split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    val query = listOfNotNull("user=$user", "password=$password".takeIf { password.isNotEmpty() }, u.query)
    return "jdbc:postgresql://${u.host}:${if (u.port > 0) u.port else 5432}${u.path}?${query.joinToString("&")}"
}

fun Application.rondo(sync: Sync, auth: Auth) {
    install(ContentNegotiation) { json(McpJson) }
    routing {
        get("/healthz") { call.respondText("ok") }
        post("/sync/pull") { call.handle(auth) { actor -> sync.pull(actor, call.receive()) } }
        post("/sync/push") { call.handle(auth) { actor -> sync.push(actor, call.receive()) } }
        post("/sync/fetch") { call.handle(auth) { actor -> sync.fetch(actor, call.receive()) } }
    }
}

private suspend inline fun <reified T : Any> ApplicationCall.handle(
    auth: Auth,
    crossinline work: suspend (String) -> T,
) {
    try {
        val protocol = request.header("Rondo-Protocol")
        if (protocol != null && protocol != "1") {
            throw Problem(426, ErrorCode.CLIENT_OUTDATED, "Update the app to keep syncing.")
        }
        val token = request.header("Authorization")?.removePrefix("Bearer ")?.trim()
        val actor = token?.let { auth.session(it) } ?: throw Problem(401, ErrorCode.UNAUTHORIZED, "Sign in again.")
        val result = withContext(Dispatchers.IO) { work(actor) }
        respond(result)
    } catch (p: Problem) {
        respond(HttpStatusCode.fromValue(p.status), ProblemBody(p.code, p.message.orEmpty()))
    } catch (e: Exception) {
        log.error("request failed", e)
        Sentry.captureException(e)
        respond(HttpStatusCode.InternalServerError, ProblemBody(ErrorCode.INTERNAL, "Something went wrong."))
    }
}

private val Actor = AttributeKey<String>("actor")

/**
 * Hosted MCP at /mcp: agents sign in through Hydra (its tokens are checked by introspection) and
 * need Pro. The tools are :mcp's, over this person's data in Postgres.
 */
fun Application.mcp(db: Db, sync: Sync, auth: Auth, origin: String, hydra: String) {
    val metadata = "$origin/.well-known/oauth-protected-resource"
    routing {
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
    intercept(ApplicationCallPipeline.Plugins) {
        if (!call.request.local.uri.startsWith("/mcp")) return@intercept
        val token = call.request.header("Authorization")?.removePrefix("Bearer ")?.trim()
        val actor = token?.let { auth.agent(it) }
        if (actor == null) {
            call.response.headers.append("WWW-Authenticate", "Bearer resource_metadata=\"$metadata\"")
            call.respond(HttpStatusCode.Unauthorized)
            return@intercept finish()
        }
        val pro =
            withContext(Dispatchers.IO) {
                db.transaction { c ->
                    c.query("SELECT 1 FROM users WHERE id = ?::uuid AND pro_until > now()", actor) { true }.isNotEmpty()
                }
            }
        if (!pro) {
            call.respond(HttpStatusCode.Forbidden, ProblemBody(ErrorCode.PRO_REQUIRED, "Agents need Pro."))
            return@intercept finish()
        }
        call.attributes.put(Actor, actor)
    }
    mcpStatelessStreamableHttp("/mcp", allowedHosts = listOf(URI(origin).host, "localhost", "127.0.0.1")) {
        rondoServer(PostgresWorkspace(call.attributes[Actor], db, sync))
    }
}
