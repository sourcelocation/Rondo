package app.rondo.client

import app.rondo.client.api.ApiApi
import app.rondo.client.api.SyncApi
import app.rondo.client.api.infrastructure.HttpResponse
import app.rondo.core.model.ErrorCode
import app.rondo.core.model.Problem
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.protocolWithAuthority
import io.ktor.serialization.kotlinx.json.json

/** A refused request: [code] from the server's problem, null when the server couldn't be reached. */
class ApiError(val status: Int, val code: ErrorCode?, message: String) : Exception(message)

/**
 * HTTP to Rondo's servers (with the session token and protocol version) and to Kratos: the
 * platform's own, or one chosen in Settings where the platform lets people choose.
 */
class Net(private val platform: Platform, private val token: () -> String?) {
    val http = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
        }
        // The protocol version goes to Rondo's servers only: Kratos's CORS would refuse the header.
        install(
            createClientPlugin("RondoProtocol") {
                onRequest { request, _ ->
                    val ours = request.url.buildString().startsWith(apiUrl)
                    if (ours) request.headers.append("Rondo-Protocol", "1")
                }
            },
        )
        expectSuccess = false
    }

    val sync: SyncApi get() = SyncApi(apiUrl, http).also { api -> token()?.let(api::setBearerToken) }

    val api: ApiApi get() = ApiApi(apiUrl, http).also { api -> token()?.let(api::setBearerToken) }

    /** The server chosen in Settings, as an origin; null for the platform's own. */
    val chosen: String? get() = platform.read(SERVER)?.takeIf { platform.serverChoice }

    /** The origin `/api`, `/sync` and `/media` are served from. */
    val apiUrl: String get() = chosen ?: platform.apiUrl

    /** Kratos: under `/auth` on a chosen server, as on every Rondo server. */
    val kratosUrl: String get() = chosen?.let { "$it/auth" } ?: platform.kratosUrl

    /**
     * Uses the Rondo server at [address] from now on, once it answers as one; blank goes back to the
     * platform's own. Only while signed out: a session, and what syncs with it, is one server's.
     */
    suspend fun useServer(address: String) {
        check(platform.serverChoice && token() == null)
        val trimmed = address.trim()
        val origin = if (trimmed.isEmpty()) platform.apiUrl else origin(trimmed)
        if (origin != platform.apiUrl) {
            val answers = origin != null && runCatching { ApiApi(origin, http).pushKey().success }.getOrDefault(false)
            if (!answers) throw SignInError("no_server", "There's no Rondo server at that address.")
        }
        platform.write(SERVER, origin.takeIf { it != platform.apiUrl })
    }

    companion object {
        const val SERVER = "server"

        /** The origin [address] names: HTTPS unless it says otherwise, without a path. */
        fun origin(address: String): String? = runCatching {
            val url = Url(if ("://" in address) address else "https://$address")
            url.protocolWithAuthority.takeIf {
                url.host.isNotEmpty() && url.protocol in setOf(URLProtocol.HTTPS, URLProtocol.HTTP)
            }
        }.getOrNull()
    }
}

/** The body of a successful response, or an [ApiError] with the server's problem. */
suspend fun <T : Any> HttpResponse<T>.ok(): T {
    if (success) return body()
    val problem = runCatching { json.decodeFromString<Problem>(response.bodyAsText()) }.getOrNull()
    throw ApiError(status, problem?.code, problem?.message ?: "HTTP $status")
}

/** Runs [call], turning a failure to connect into an [ApiError] without a code. */
suspend fun <T> online(call: suspend () -> T): T = try {
    call()
} catch (e: ApiError) {
    throw e
} catch (e: Throwable) {
    // Ktor's browser engine reports a failed fetch as an Error, not an Exception.
    if (e is kotlinx.coroutines.CancellationException) throw e
    throw ApiError(0, null, e.message ?: "offline")
}
