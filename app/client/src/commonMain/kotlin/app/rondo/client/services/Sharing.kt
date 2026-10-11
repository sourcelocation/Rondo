package app.rondo.client.services

import app.rondo.client.net.ApiError
import app.rondo.client.net.Net
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Sharing a deck: the people it's lent to, invitations, links, and Discover. */
class Sharing(private val net: Net) {
    suspend fun shares(deck: String): List<Share> = net.get("/api/decks/$deck/shares").read()

    /** Lends [deck] to [user] as [role]: 1 viewer, 2 editor. */
    suspend fun setRole(deck: String, user: String, role: Int) {
        net.put("/api/decks/$deck/shares/$user", RoleUpdate(role))
    }

    /** Stops lending [deck] to [user]; for yourself, stops borrowing it. */
    suspend fun remove(deck: String, user: String) {
        net.delete("/api/decks/$deck/shares/$user")
    }

    suspend fun invites(deck: String): List<Invite> = net.get("/api/decks/$deck/invites").read()

    /** Invites [email] to [deck] as [role]; with no email, a link anyone can use. */
    suspend fun invite(deck: String, role: Int, email: String?): Invite =
        net.post("/api/decks/$deck/invites", InviteRequest(role, email)).read()

    suspend fun revoke(invite: String) {
        net.delete("/api/invites/$invite")
    }

    /** What an invitation's [token] is for; null when it's gone. */
    suspend fun invitation(token: String): Invitation? = try {
        net.get("/api/invitations/$token").read<InvitationMessage>().let { Invitation(it.deck.deck(), it.role) }
    } catch (e: ApiError) {
        if (e.status != 404) throw e
        null
    }

    /** Joins the deck an invitation is for; its id. */
    suspend fun accept(token: String): String = net.post("/api/invitations/$token/accept").read<Lent>().deckId

    /** [deck] on Discover, or null when it isn't. */
    suspend fun publication(deck: String): Publication? = try {
        net.get("/api/decks/$deck/publication").read<Publication>()
    } catch (e: ApiError) {
        if (e.status != 404) throw e
        null
    }

    suspend fun publish(deck: String) {
        net.put("/api/decks/$deck/publication")
    }

    suspend fun unpublish(deck: String) {
        net.delete("/api/decks/$deck/publication")
    }

    @Serializable
    private class RoleUpdate(val role: Int)

    @Serializable
    private class InviteRequest(val role: Int, val email: String?)

    @Serializable
    private class InvitationMessage(val deck: PublicDeckMessage, val role: Int)
}

/** Someone a deck is lent to, as [role]: 1 viewer, 2 editor. */
@Serializable
class Share(
    @SerialName("user_id") val userId: String,
    val name: String? = null,
    val username: String? = null,
    val role: Int,
)

/** An invitation: to an [email], or a link ([url]); [expiresAt] ISO. */
@Serializable
class Invite(
    val id: String,
    val role: Int,
    val email: String? = null,
    val url: String? = null,
    @SerialName("expires_at") val expiresAt: String,
)

/** A deck an invitation is for, and the [role] it gives. */
class Invitation(val deck: PublicDeck, val role: Int)

/** A deck on Discover: its [slug], [followers], and whether moderators [removed] it. */
@Serializable
class Publication(
    @SerialName("deck_id") val deckId: String,
    val slug: String,
    val followers: Int = 0,
    val removed: Boolean? = null,
)
