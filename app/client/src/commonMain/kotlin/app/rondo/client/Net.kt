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
import io.ktor.serialization.kotlinx.json.json

/** A refused request: [code] from the server's problem, null when the server couldn't be reached. */
class ApiError(val status: Int, val code: ErrorCode?, message: String) : Exception(message)

/** HTTP to Rondo's servers (with the session token and protocol version) and to Kratos. */
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
                    val ours = request.url.buildString().startsWith(platform.apiUrl)
                    if (ours) request.headers.append("Rondo-Protocol", "1")
                }
            },
        )
        expectSuccess = false
    }

    val sync: SyncApi get() = SyncApi(platform.apiUrl, http).also { api -> token()?.let(api::setBearerToken) }

    val api: ApiApi get() = ApiApi(platform.apiUrl, http).also { api -> token()?.let(api::setBearerToken) }

    val kratosUrl: String get() = platform.kratosUrl
    val apiUrl: String get() = platform.apiUrl
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
