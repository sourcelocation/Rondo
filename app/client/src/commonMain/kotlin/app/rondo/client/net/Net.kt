package app.rondo.client.net

import app.rondo.client.Platform
import app.rondo.core.kernel.domain.ErrorCode
import app.rondo.core.sync.infrastructure.Message
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.http.protocolWithAuthority
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Why signing in stopped. [code]: no_account, wrong_code, expired, cancelled, reauthenticate,
 * no_server (choosing one) or failed.
 */
class SignInError(val code: String, message: String) : Exception(message)

/** A refused request: [code] from the server's problem, null when the server couldn't be reached. */
class ApiError(val status: Int, val code: ErrorCode?, message: String) : Exception(message)

/** What the server answered: [read] it as what the call returns. */
class Answer(@PublishedApi internal val response: HttpResponse) {
    val status: Int get() = response.status.value

    suspend inline fun <reified T> read(): T = Net.JSON.decodeFromString(serializer<T>(), response.bodyAsText())
}

/**
 * HTTP to Rondo's servers (with the session token and the protocol version) and to Kratos: the
 * platform's own, or one chosen in Settings where the platform lets people choose. Each call to
 * the Go server is written beside the service that makes it, with its messages; a refusal comes
 * back as an [ApiError] with the server's code, and a server out of reach as one without.
 */
class Net(private val platform: Platform, private val token: () -> String?) {
    val http = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
        }
        // The protocol version goes to Rondo's servers only: Kratos's CORS would refuse the header.
        install(
            createClientPlugin("RondoProtocol") {
                onRequest { request, _ ->
                    val ours = request.url.buildString().startsWith(apiUrl)
                    if (ours) request.headers.append("Rondo-Protocol", PROTOCOL)
                }
            },
        )
        expectSuccess = false
    }

    /** The server chosen in Settings, as an origin; null for the platform's own. */
    val chosen: String? get() = platform.read(SERVER)?.takeIf { platform.serverChoice }

    /** The origin `/api`, `/sync` and `/media` are served from. */
    val apiUrl: String get() = chosen ?: platform.apiUrl

    /** Kratos: under `/auth` on a chosen server, as on every Rondo server. */
    val kratosUrl: String get() = chosen?.let { "$it/auth" } ?: platform.kratosUrl

    suspend fun get(path: String, vararg query: Pair<String, Any?>): Answer = call(HttpMethod.Get, path) {
        for ((k, v) in query) if (v != null) parameter(k, v)
    }

    suspend fun post(path: String): Answer = call(HttpMethod.Post, path) {}

    suspend inline fun <reified B : Any> post(path: String, body: B): Answer = call(HttpMethod.Post, path) {
        json(body)
    }

    suspend fun put(path: String): Answer = call(HttpMethod.Put, path) {}

    suspend inline fun <reified B : Any> put(path: String, body: B): Answer = call(HttpMethod.Put, path) { json(body) }

    suspend fun delete(path: String): Answer = call(HttpMethod.Delete, path) {}

    @PublishedApi
    internal inline fun <reified B : Any> HttpRequestBuilder.json(body: B) {
        setBody(TextContent(JSON.encodeToString(serializer<B>(), body), ContentType.Application.Json))
    }

    /** [method] on [path] at Rondo's server, as the signed-in account; a refusal throws. */
    @PublishedApi
    internal suspend fun call(method: HttpMethod, path: String, setup: HttpRequestBuilder.() -> Unit): Answer {
        val response = online {
            http.request("$apiUrl$path") {
                this.method = method
                token()?.let { bearerAuth(it) }
                setup()
            }
        }
        if (response.status.isSuccess()) return Answer(response)
        val problem = runCatching { JSON.decodeFromString(Problem.serializer(), response.bodyAsText()) }.getOrNull()
        val code = problem?.code?.let { c -> ErrorCode.entries.firstOrNull { it.value == c } }
        throw ApiError(response.status.value, code, problem?.message ?: "HTTP ${response.status.value}")
    }

    /** Runs [call], turning a failure to connect into an [ApiError] without a code. */
    suspend fun <T> online(call: suspend () -> T): T = try {
        call()
    } catch (e: ApiError) {
        throw e
    } catch (e: SignInError) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // Ktor's browser engine reports a failed fetch as an Error, not an Exception.
        throw ApiError(0, null, e.message ?: "offline")
    }

    /**
     * Uses the Rondo server at [address] from now on, once it answers as one; blank goes back to the
     * platform's own. Only while signed out: a session, and what syncs with it, is one server's.
     */
    suspend fun useServer(address: String) {
        check(platform.serverChoice && token() == null)
        val trimmed = address.trim()
        val origin = if (trimmed.isEmpty()) platform.apiUrl else origin(trimmed)
        if (origin != platform.apiUrl) {
            val answers = origin != null && runCatching {
                http.request("$origin/api/push/key") { method = HttpMethod.Get }.status.isSuccess()
            }.getOrDefault(false)
            if (!answers) throw SignInError("no_server", "There's no Rondo server at that address.")
        }
        platform.write(SERVER, origin.takeIf { it != platform.apiUrl })
    }

    /** The origin [address] names: HTTPS unless it says otherwise, without a path. */
    internal fun origin(address: String): String? = runCatching {
        val url = Url(if ("://" in address) address else "https://$address")
        url.protocolWithAuthority.takeIf {
            url.host.isNotEmpty() && url.protocol in setOf(URLProtocol.HTTPS, URLProtocol.HTTP)
        }
    }.getOrNull()

    /** The server's answer to a request it refused. */
    @Serializable
    private class Problem(val code: String? = null, val message: String? = null)

    companion object {
        const val SERVER = "server"

        /** The sync protocol this app speaks: the server refuses older ones with 426. */
        const val PROTOCOL = Message.VERSION

        /**
         * JSON as Rondo's servers and Kratos write it: unknown fields skipped, absent ones left out,
         * and sync's records tagged by kind.
         */
        val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            serializersModule = Message.json.serializersModule
        }
    }
}
