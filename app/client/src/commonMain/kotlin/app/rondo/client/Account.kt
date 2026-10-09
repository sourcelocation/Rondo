package app.rondo.client

import app.rondo.core.model.Me
import app.rondo.core.model.NameUpdate
import app.rondo.core.model.ProfileUpdate
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Why signing in stopped. [code]: no_account, wrong_code, expired, cancelled, reauthenticate,
 * no_server (choosing one) or failed.
 */
class SignInError(val code: String, message: String) : Exception(message)

/** A sign-in waiting for its email code. */
class CodeFlow(val email: String, internal val kind: String, internal val id: String)

/** Turning on the authenticator app: [qr] is an image (a data URL) to scan, [key] to type instead. */
class TotpSetup(val qr: String, val key: String, internal val flow: JsonObject)

/** Recovery codes just made, each good once instead of the authenticator app. */
class RecoveryCodes(val codes: List<String>, internal val flow: JsonObject)

/** A device the account is signed in on: what it is, roughly where, and since when (ISO). */
class SignedIn(val id: String, val device: String, val place: String?, val since: String?, val current: Boolean) {
    companion object {
        internal fun of(session: JsonObject, current: Boolean): SignedIn {
            val device = session["devices"]?.jsonArray?.firstOrNull()?.jsonObject
            val agent = device?.get("user_agent")?.jsonPrimitive?.content.orEmpty()
            val place = device?.get("location")?.jsonPrimitive?.content?.ifBlank { null }
            val since = session["authenticated_at"]?.jsonPrimitive?.content
            return SignedIn(session["id"]?.jsonPrimitive?.content.orEmpty(), describe(agent), place, since, current)
        }

        private val browsers = listOf(
            "Edg" to "Edge",
            "Firefox" to "Firefox",
            "Chrome" to "Chrome",
            "Safari" to "Safari",
        )
        private val systems = listOf(
            "iPhone" to "iPhone",
            "iPad" to "iPad",
            "Android" to "Android",
            "Mac OS" to "macOS",
            "Windows" to "Windows",
            "Linux" to "Linux",
        )

        /** A user agent in words: the browser and the system, or the app. */
        internal fun describe(agent: String): String {
            val browser = browsers.firstOrNull { agent.contains(it.first) }?.second
            val system = systems.firstOrNull { agent.contains(it.first) }?.second
            val known = listOfNotNull(browser, system).joinToString(" · ")
            return known.ifEmpty { if (agent.contains("Ktor")) "Rondo" else agent.take(40) }
        }
    }
}

/** A passkey ceremony: [options] is WebAuthn JSON for the platform; return its credential JSON. */
class PasskeyFlow(val options: String, internal val kind: String, internal val id: String)

/**
 * The account on this device, through Kratos's API flows (the same on every platform): email codes,
 * passkeys and Apple/Google ID tokens. Without an account everything works except syncing.
 */
class Account(private val platform: Platform, private val store: Store, private val net: Net) {
    val token: String? get() = platform.read(TOKEN)
    val signedIn: Boolean get() = token != null
    var me: Me? = null
        private set
    var email: String? = null
        private set

    suspend fun load() {
        me = store.meta("me")?.let { json.decodeFromString<Me>(it) }
        email = store.meta("email")
    }

    suspend fun refresh(): Me? = runCatching { online { net.api.getMe().ok() } }.getOrNull()?.also {
        me = it
        store.putMeta("me", json.encodeToString(it))
    }

    suspend fun setName(name: String): Me = online { net.api.setName(NameUpdate(name.trim())).ok() }.also {
        me = it
        store.putMeta("me", json.encodeToString(it))
    }

    // Email codes --------------------------------------------------------------------------------

    /** Sends a code to [email]: signing in if the account exists, signing up otherwise. */
    suspend fun sendCode(email: String): CodeFlow {
        val login = start("login")
        val sent = submit(
            "login",
            login,
            buildJsonObject {
                put("method", "code")
                put("identifier", email)
            },
        )
        if (messages(sent).none { it == NO_ACCOUNT }) return CodeFlow(email, "login", login)
        val registration = start("registration")
        submit(
            "registration",
            registration,
            buildJsonObject {
                put("method", "code")
                putJsonObject("traits") { put("email", email) }
            },
        )
        return CodeFlow(email, "registration", registration)
    }

    suspend fun confirm(flow: CodeFlow, code: String) {
        val body = buildJsonObject {
            put("method", "code")
            put("code", code.trim())
            if (flow.kind == "login") {
                put("identifier", flow.email)
            } else {
                putJsonObject("traits") { put("email", flow.email) }
            }
        }
        finish(submit(flow.kind, flow.id, body))
    }

    // Passkeys and providers ---------------------------------------------------------------------

    suspend fun passkey(): PasskeyFlow {
        val login = start("login")
        val options =
            node(flowOf("login", login), "passkey_challenge")
                ?: throw SignInError("failed", "Passkeys aren't available right now.")
        return PasskeyFlow(options, "login", login)
    }

    suspend fun finishPasskey(flow: PasskeyFlow, credential: String) = when (flow.kind) {
        "login" -> finish(
            submit(
                "login",
                flow.id,
                buildJsonObject {
                    put("method", "passkey")
                    put("passkey_login", credential)
                },
            ),
        )

        else -> {
            submit(
                "settings",
                flow.id,
                buildJsonObject {
                    put("method", "passkey")
                    put("passkey_settings_register", credential)
                },
                token,
            )
            Unit
        }
    }

    /** Adds a passkey to the signed-in account. */
    suspend fun addPasskey(): PasskeyFlow {
        val settings = start("settings", token)
        val options =
            node(flowOf("settings", settings, token), "passkey_create_data")
                ?: throw SignInError("failed", "Passkeys aren't available right now.")
        return PasskeyFlow(options, "settings", settings)
    }

    /**
     * Signs in again with a passkey, keeping this session: staff tools need a session that used one.
     * Throws [SignInError] `no_passkey` when the account has none yet.
     */
    suspend fun confirmWithPasskey() {
        val response =
            online {
                net.http.get("${net.kratosUrl}/self-service/login/api") {
                    parameter("refresh", "true")
                    header("X-Session-Token", token)
                }
            }
        val flow = parse(response)
        val id = flow["id"]!!.jsonPrimitive.content
        val options = node(flow, "passkey_challenge")
            ?: throw SignInError("no_passkey", "Add a passkey in Settings › Security first.")
        val credential = platform.passkey(options, register = false)
        val body = buildJsonObject {
            put("method", "passkey")
            put("passkey_login", credential)
        }
        val result = submit("login", id, body, token)
        result["session_token"]?.jsonPrimitive?.content?.let { platform.write(TOKEN, it) }
        refresh()
    }

    // The second step ---------------------------------------------------------------------------

    /** Whether this session still needs its second step: an authenticator code or a recovery code. */
    suspend fun needsSecondFactor(): Boolean {
        if (!signedIn) return false
        val response =
            online { net.http.get("${net.kratosUrl}/sessions/whoami") { header("X-Session-Token", token) } }
        return response.status.value == 403 && "session_aal2_required" in response.bodyAsText()
    }

    /** Takes the second step with a [code] from the authenticator app, or a [recovery] code. */
    suspend fun secondFactor(code: String, recovery: Boolean) {
        val response =
            online {
                net.http.get("${net.kratosUrl}/self-service/login/api") {
                    parameter("aal", "aal2")
                    header("X-Session-Token", token)
                }
            }
        val id = parse(response)["id"]!!.jsonPrimitive.content
        val body = buildJsonObject {
            if (recovery) {
                put("method", "lookup_secret")
                put("lookup_secret", code.trim())
            } else {
                put("method", "totp")
                put("totp_code", code.trim())
            }
        }
        val result = submit("login", id, body, token)
        if (result["session"] == null) throw SignInError("wrong_code", "That code isn't right.")
        refresh()
    }

    /** A settings flow: what Kratos lets this account change, and how. */
    private suspend fun settingsFlow(): JsonObject = parse(
        online {
            net.http.get("${net.kratosUrl}/self-service/settings/api") { header("X-Session-Token", token) }
        },
    )

    private suspend fun change(flow: JsonObject, body: JsonObject): JsonObject {
        val result = submit("settings", flow["id"]!!.jsonPrimitive.content, body, token)
        if (result["state"]?.jsonPrimitive?.content != "success") {
            throw SignInError("wrong_code", text(result) ?: "That didn't work. Try again.")
        }
        return result
    }

    /** Whether the authenticator app is on. */
    suspend fun twoFactor(): Boolean = attributes(settingsFlow(), "totp_unlink") != null

    /** Starts turning on the authenticator app: the QR code to scan and its key, to type instead. */
    suspend fun startTotp(): TotpSetup {
        val flow = settingsFlow()
        val qr = attributes(flow, "totp_qr")?.get("src")?.jsonPrimitive?.content
            ?: throw SignInError("failed", "The authenticator app is on already.")
        val key = attributes(flow, "totp_secret_key")?.get("text")?.jsonObject?.get("text")?.jsonPrimitive?.content
        return TotpSetup(qr, key.orEmpty(), flow)
    }

    /** Turns the authenticator app on with a [code] it shows. */
    suspend fun confirmTotp(setup: TotpSetup, code: String) {
        change(
            setup.flow,
            buildJsonObject {
                put("method", "totp")
                put("totp_code", code.trim())
            },
        )
    }

    /** Turns the authenticator app off, and the recovery codes with it. */
    suspend fun disableTotp() {
        change(
            settingsFlow(),
            buildJsonObject {
                put("method", "totp")
                put("totp_unlink", true)
            },
        )
        runCatching {
            change(
                settingsFlow(),
                buildJsonObject {
                    put("method", "lookup_secret")
                    put("lookup_secret_disable", true)
                },
            )
        }
    }

    /** New recovery codes, replacing any before: to show once, then [keepRecoveryCodes]. */
    suspend fun newRecoveryCodes(): RecoveryCodes {
        val flow = change(
            settingsFlow(),
            buildJsonObject {
                put("method", "lookup_secret")
                put("lookup_secret_regenerate", true)
            },
        )
        val secrets = attributes(flow, "lookup_secret_codes")?.get("text")?.jsonObject?.get("context")?.jsonObject
            ?.get("secrets")?.jsonArray.orEmpty()
        val codes = secrets.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
        return RecoveryCodes(codes, flow)
    }

    /** Keeps recovery codes just shown. */
    suspend fun keepRecoveryCodes(codes: RecoveryCodes) {
        change(
            codes.flow,
            buildJsonObject {
                put("method", "lookup_secret")
                put("lookup_secret_confirm", true)
            },
        )
    }

    // Devices -------------------------------------------------------------------------------------

    /** Where this account is signed in: this device first. */
    suspend fun devices(): List<SignedIn> {
        val headers: io.ktor.client.request.HttpRequestBuilder.() -> Unit = { header("X-Session-Token", token) }
        val current = parse(online { net.http.get("${net.kratosUrl}/sessions/whoami", headers) })
        val others = runCatching {
            val response = online { net.http.get("${net.kratosUrl}/sessions", headers) }
            json.parseToJsonElement(response.bodyAsText()).jsonArray
        }.getOrNull().orEmpty()
        return listOf(SignedIn.of(current, true)) + others.map { SignedIn.of(it.jsonObject, false) }
    }

    /** Signs one other device out. */
    suspend fun signOutDevice(id: String) {
        online { net.http.delete("${net.kratosUrl}/sessions/$id") { header("X-Session-Token", token) } }
    }

    /** Signs every other device out. */
    suspend fun signOutOthers() {
        online { net.http.delete("${net.kratosUrl}/sessions") { header("X-Session-Token", token) } }
    }

    /** Reads the account's email again, after it changed. */
    suspend fun refreshEmail() {
        val session = parse(
            online { net.http.get("${net.kratosUrl}/sessions/whoami") { header("X-Session-Token", token) } },
        )
        val traits = session["identity"]?.jsonObject?.get("traits")?.jsonObject
        traits?.get("email")?.jsonPrimitive?.content?.let {
            email = it
            store.putMeta("email", it)
        }
    }

    /** Forgets a notification once it was shown, so this device doesn't show it again. */
    internal suspend fun forget(notification: String) {
        val current = me ?: return
        me = current.copy(notifications = current.notifications?.filter { it.id != notification })
        store.putMeta("me", json.encodeToString(me))
    }

    /** Signs in with an ID token from Apple's or Google's own sign-in. */
    suspend fun idToken(provider: String, idToken: String, nonce: String?) {
        nameIn(idToken)?.let { store.putMeta(SUGGESTED_NAME, it) }
        val login = start("login")
        finish(
            submit(
                "login",
                login,
                buildJsonObject {
                    put("method", "oidc")
                    put("provider", provider)
                    put("id_token", idToken)
                    nonce?.let { put("id_token_nonce", it) }
                },
            ),
        )
    }

    /** Confirms who you are again (deleting the account needs a recent sign-in): sends a fresh code. */
    suspend fun reauthenticate(email: String): CodeFlow {
        val response =
            online {
                net.http.get("${net.kratosUrl}/self-service/login/api") {
                    parameter("refresh", "true")
                    header("X-Session-Token", token)
                }
            }
        val id = parse(response)["id"]!!.jsonPrimitive.content
        submit(
            "login",
            id,
            buildJsonObject {
                put("method", "code")
                put("identifier", email)
            },
        )
        return CodeFlow(email, "login", id)
    }

    // Signing in and out -------------------------------------------------------------------------

    /** Uses a session obtained elsewhere (the command-line client's sign-in, tests). */
    suspend fun useSession(session: String, identity: String) {
        platform.write(TOKEN, session)
        adopt(identity)
        refresh()
    }

    private suspend fun finish(result: JsonObject) {
        val session = result["session_token"]?.jsonPrimitive?.content
        val identity = result["session"]?.jsonObject?.get("identity")?.jsonObject?.get("id")?.jsonPrimitive?.content
        if (session == null || identity == null) {
            val ids = messages(result)
            throw when {
                WRONG_CODE in ids -> SignInError("wrong_code", "That code isn't right, or it expired.")
                else -> SignInError("failed", text(result) ?: "Signing in didn't work. Try again.")
            }
        }
        platform.write(TOKEN, session)
        adopt(identity)
        result["session"]?.jsonObject?.get(
            "identity",
        )?.jsonObject?.get("traits")?.jsonObject?.get("email")?.jsonPrimitive?.content?.let {
            email = it
            store.putMeta("email", it)
        }
        refresh()
    }

    /** Rows made before signing in become the account's, so they sync up. */
    private suspend fun adopt(identity: String) {
        val placeholder = store.me
        if (placeholder == identity) return
        store.db.transaction {
            store.q.adoptDecks(identity, placeholder)
            store.q.adoptTemplates(identity, placeholder)
            store.q.adoptNotes(identity, placeholder)
            store.q.adoptSmartDecks(identity, placeholder)
            store.putMeta("account", identity)
        }
        store.becomes(identity)
        store.save(store.settings().copy(userId = identity, v = store.clock()))
    }

    /** Ends the session and removes this account's data from the device. */
    suspend fun signOut() {
        token?.let { t ->
            runCatching {
                net.http.delete("${net.kratosUrl}/self-service/logout/api") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"session_token":"$t"}""")
                }
            }
        }
        platform.write(TOKEN, null)
        me = null
        email = null
        store.q.wipe()
        store.becomes(app.rondo.core.Ids.new().also { store.putMeta("account", it) })
    }

    suspend fun deleteAccount() {
        val response = online { net.api.deleteMe() }
        if (response.status == 403) throw SignInError("reauthenticate", "Confirm it's you first.")
        response.ok()
        platform.write(TOKEN, null)
        signOut()
    }

    // Kratos -------------------------------------------------------------------------------------

    private suspend fun start(kind: String, session: String? = null): String =
        flowOf(kind, null, session)["id"]?.jsonPrimitive?.content
            ?: throw SignInError("failed", "Signing in isn't available right now.")

    private suspend fun flowOf(kind: String, id: String?, session: String? = null): JsonObject = parse(
        online {
            net.http.get("${net.kratosUrl}/self-service/$kind/${if (id == null) "api" else "flows"}") {
                id?.let { parameter("id", it) }
                session?.let { header("X-Session-Token", it) }
            }
        },
    )

    private suspend fun submit(kind: String, flow: String, body: JsonObject, session: String? = null): JsonObject {
        val response = online {
            net.http.post("${net.kratosUrl}/self-service/$kind") {
                parameter("flow", flow)
                session?.let { header("X-Session-Token", it) }
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        if (response.status.value == 410) throw SignInError("expired", "That took too long. Start again.")
        if (response.status.value == 403) throw SignInError("reauthenticate", "Confirm it's you first.")
        return parse(response)
    }

    private suspend fun parse(response: HttpResponse): JsonObject {
        val body = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject }
            .getOrElse { throw SignInError("failed", "The sign-in service didn't answer.") }
        // A flow that needs more input comes back as the flow; anything else wrong, as an error.
        body["error"]?.jsonObject?.let {
            val reason = it["reason"]?.jsonPrimitive?.content ?: "Signing in didn't work."
            // Kratos refuses identities staff deactivated (a ban) with this message.
            val suspended = it["message"]?.jsonPrimitive?.content == "identity is disabled"
            throw SignInError(if (suspended) "suspended" else "failed", reason)
        }
        return body
    }

    private fun ui(flow: JsonObject): JsonObject? = flow["ui"]?.jsonObject

    /** Kratos's message ids: the flow's own and its fields'. */
    private fun messages(flow: JsonObject): List<Int> {
        val ui = ui(flow)
        val fields = ui?.get("nodes")?.jsonArray.orEmpty().flatMap { it.jsonObject["messages"]?.jsonArray.orEmpty() }
        val all = ui?.get("messages")?.jsonArray.orEmpty() + fields
        return all.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.int }
    }

    private fun text(flow: JsonObject): String? =
        ui(flow)?.get("messages")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content

    /** A flow's node by its name or id: its attributes. */
    private fun attributes(flow: JsonObject, name: String): JsonObject? = ui(flow)?.get("nodes")?.jsonArray
        ?.mapNotNull { it.jsonObject["attributes"]?.jsonObject }
        ?.firstOrNull { name in listOf("name", "id").map { key -> it[key]?.jsonPrimitive?.content } }

    private fun node(flow: JsonObject, name: String): String? = ui(flow)?.get("nodes")?.jsonArray?.map { it.jsonObject }
        ?.firstOrNull { it["attributes"]?.jsonObject?.get("name")?.jsonPrimitive?.content == name }
        ?.get("attributes")?.jsonObject?.get("value")?.let {
            if (it is kotlinx.serialization.json.JsonPrimitive) it.content else it.toString()
        }

    /** The name an ID token carries (Google's do), to suggest when the profile is set up. */
    private fun nameIn(idToken: String): String? = runCatching {
        val payload = idToken.split('.')[1]
        val padded = payload.replace('-', '+').replace('_', '/').padEnd((payload.length + 3) / 4 * 4, '=')
        val claims = json.parseToJsonElement(Base64.decode(padded).decodeToString()).jsonObject
        claims["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** The name to suggest for the profile, from the sign-in. */
    suspend fun suggestedName(): String? = store.meta(SUGGESTED_NAME)

    /** Changes the profile: username, display name, flag, whether activity shows; [confirm] confirms it. */
    suspend fun updateProfile(update: ProfileUpdate): Me = online { net.api.updateProfile(update).ok() }.also {
        me = it
        store.putMeta("me", json.encodeToString(it))
    }

    companion object {
        const val TOKEN = "session"
        private const val SUGGESTED_NAME = "suggested_name"
        private const val NO_ACCOUNT = 4000035
        private const val WRONG_CODE = 4010008
    }
}
