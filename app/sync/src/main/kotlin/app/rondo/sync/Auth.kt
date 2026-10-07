package app.rondo.sync

import app.rondo.core.model.ErrorCode
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Who a bearer token belongs to: a Kratos session (apps) or a Hydra access token (agents), each
 * checked with its issuer and remembered for a minute.
 */
open class Auth(private val kratos: String, private val hydraAdmin: String?, private val audience: String) {
    private val http = HttpClient(CIO)
    private val cache = ConcurrentHashMap<String, Pair<String?, Long>>()

    open suspend fun session(token: String): String? = cached("s:$token") {
        val response = http.get("$kratos/sessions/whoami") { header("X-Session-Token", token) }
        // Signed in, but the person turned on a second step this session hasn't taken yet.
        if (response.status.value == 403 && "session_aal2_required" in response.bodyAsText()) {
            throw Problem(403, ErrorCode.SECOND_FACTOR_REQUIRED, "Enter the code from your authenticator app.")
        }
        if (!response.status.isSuccess()) return@cached null
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val active = body["active"]?.jsonPrimitive?.boolean == true
        body["identity"]?.jsonObject?.get("id")?.jsonPrimitive?.content.takeIf { active }
    }

    open suspend fun agent(token: String): String? = cached("a:$token") {
        val admin = hydraAdmin ?: return@cached null
        val response = http.submitForm("$admin/admin/oauth2/introspect", parameters { append("token", token) })
        if (!response.status.isSuccess()) return@cached null
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val audiences = body["aud"]?.toString().orEmpty()
        val active = body["active"]?.jsonPrimitive?.boolean == true
        body["sub"]?.jsonPrimitive?.content.takeIf { active && audience in audiences }
    }

    private suspend fun cached(key: String, load: suspend () -> String?): String? {
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { it.second > now }?.let { return it.first }
        if (cache.size > 50_000) cache.clear()
        return load().also { cache[key] = it to now + 60_000 }
    }
}
