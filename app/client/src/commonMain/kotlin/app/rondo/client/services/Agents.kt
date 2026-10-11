package app.rondo.client.services

import app.rondo.client.net.ApiError
import app.rondo.client.net.Net
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Agents using the account through OAuth: asking for access, allowed, and disconnected. */
class Agents(private val net: Net) {
    /** The agents allowed in, with when. */
    suspend fun all(): List<Agent> = net.get("/api/agents").read()

    /** Disconnects an agent: it can't use the account until it's allowed again. */
    suspend fun revoke(clientId: String) {
        net.delete("/api/agents/$clientId")
    }

    /** What an agent's request ([challenge]) asks for; null when the request is gone. */
    suspend fun consent(challenge: String): Consent? = try {
        net.get("/api/oauth/consent/$challenge").read<Consent>()
    } catch (e: ApiError) {
        if (e.status != 404) throw e
        null
    }

    /** Allows or denies the request; the page to go back to. */
    suspend fun decide(challenge: String, allow: Boolean): String =
        net.post("/api/oauth/consent/$challenge", ConsentDecision(allow)).read<Redirect>().url

    /** Signs this account in for an agent's request (Hydra's login step); the page that asks consent. */
    suspend fun acceptLogin(challenge: String): String = net.put("/api/oauth/login/$challenge").read<Redirect>().url

    @Serializable
    private class ConsentDecision(val accept: Boolean)
}

/** Where to go next. */
@Serializable
class Redirect(val url: String)

/** An agent allowed in: [grantedAt] ISO. */
@Serializable
class Agent(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_name") val clientName: String,
    @SerialName("granted_at") val grantedAt: String,
)

/** What an agent asks for. */
@Serializable
class Consent(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_name") val clientName: String,
    @SerialName("client_uri") val clientUri: String? = null,
    val scopes: List<String> = emptyList(),
)
