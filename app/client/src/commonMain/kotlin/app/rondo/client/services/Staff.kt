package app.rondo.client.services

import app.rondo.client.net.Net
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Staff tools: people, the team, the log, the moderation queue and promo codes. Every change staff
 * make is one call, [act], and goes in the log.
 */
class Staff(private val net: Net) {
    /**
     * Runs a staff action of [kind] on a user, a deck or a case, for [reason] with a [note] they see;
     * [days] how long it lasts (0: for good); [data] what else the kind needs.
     */
    suspend fun act(
        kind: String,
        userId: String? = null,
        deckId: String? = null,
        reason: String? = null,
        note: String? = null,
        days: Int = 0,
        data: Map<String, String>? = null,
        caseId: String? = null,
    ): StaffAction {
        val until = if (days > 0) (Clock.System.now() + days.days).toString() else null
        return net.post(
            "/api/staff/actions",
            ActionRequest(
                kind,
                userId,
                deckId,
                reason,
                note?.ifBlank {
                    null
                },
                until,
                data,
                caseId,
            ),
        ).read()
    }

    /** People by username, name or id ([by] name), by email, or by address (ip). */
    suspend fun search(query: String, by: String): List<StaffUserItem> = when (by) {
        "email" -> net.get("/api/staff/users", "email" to query)
        "ip" -> net.get("/api/staff/users", "ip" to query)
        else -> net.get("/api/staff/users", "q" to query)
    }.read()

    suspend fun user(id: String): StaffUser = net.get("/api/staff/users/$id").read()

    suspend fun team(): List<TeamMember> = net.get("/api/staff/team").read()

    /** The log, newest first: everyone's, or about an [actor], a [user] or a [deck]; older than [before]. */
    suspend fun log(actor: String?, user: String?, deck: String?, before: String?): ActionPage =
        net.get("/api/staff/actions", "actor" to actor, "user" to user, "deck" to deck, "before" to before).read()

    suspend fun action(id: String): StaffAction = net.get("/api/staff/actions/$id").read()

    /** Undoes an entry of the log, saying why. */
    suspend fun undo(id: String, reason: String): StaffAction =
        net.post("/api/staff/actions/$id/undo", UndoRequest(reason)).read()

    /** The open cases, the most serious lanes first; one [lane], or all. */
    suspend fun cases(lane: String?): List<CaseItem> = net.get("/api/staff/cases", "lane" to lane).read()

    suspend fun case(id: String): Case = net.get("/api/staff/cases/$id").read()

    suspend fun batches(): List<PromoBatch> = net.get("/api/staff/promo").read()

    suspend fun batch(id: String): PromoBatchCodes = net.get("/api/staff/promo/$id").read()

    @Serializable
    private class ActionRequest(
        val kind: String,
        @SerialName("user_id") val userId: String?,
        @SerialName("deck_id") val deckId: String?,
        val reason: String?,
        val note: String?,
        val until: String?,
        val data: Map<String, String>?,
        @SerialName("case_id") val caseId: String?,
    )

    @Serializable
    private class UndoRequest(val reason: String)
}

/** Someone as staff pages name them. */
@Serializable
class UserRef(val id: String, val username: String? = null, val name: String? = null, val flag: String? = null)

/** An entry of the staff log. Times are ISO. */
@Serializable
class StaffAction(
    val id: String,
    val kind: String,
    val actor: UserRef? = null,
    val user: UserRef? = null,
    @SerialName("deck_id") val deckId: String? = null,
    @SerialName("deck_name") val deckName: String? = null,
    val reason: String? = null,
    val note: String? = null,
    val until: String? = null,
    val data: Map<String, String> = emptyMap(),
    @SerialName("case_id") val caseId: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("undone_at") val undoneAt: String? = null,
    @SerialName("undone_by") val undoneBy: UserRef? = null,
    @SerialName("undo_reason") val undoReason: String? = null,
    val undoable: Boolean = false,
)

@Serializable
class ActionPage(val items: List<StaffAction>, val more: Boolean)

@Serializable
class TeamMember(val user: UserRef, val roles: List<String>)

/** Someone in search results. Times are ISO. */
@Serializable
class StaffUserItem(
    val user: UserRef,
    val joined: String,
    val roles: List<String> = emptyList(),
    @SerialName("restricted_until") val restrictedUntil: String? = null,
    @SerialName("banned_until") val bannedUntil: String? = null,
)

/**
 * Someone as staff see them: their standing, the ladder's next step ([suggestedKind],
 * [suggestedDays]), and the private details the staff member's permissions show. Times are ISO.
 */
@Serializable
class StaffUser(
    val user: UserRef,
    val joined: String,
    val pro: Boolean = false,
    val roles: List<String> = emptyList(),
    @SerialName("restricted_until") val restrictedUntil: String? = null,
    @SerialName("banned_until") val bannedUntil: String? = null,
    val recent: Int = 0,
    @SerialName("suggested_kind") val suggestedKind: String,
    @SerialName("suggested_days") val suggestedDays: Int? = null,
    val email: String? = null,
    @SerialName("email_verified") val emailVerified: Boolean? = null,
    @SerialName("sign_in") val signIn: List<String>? = null,
    val ips: List<Address>? = null,
    val names: List<Name>? = null,
    val related: List<Related>? = null,
    val billing: Subscription? = null,
) {
    @Serializable
    class Address(
        val ip: String,
        @SerialName("first_at") val firstAt: String,
        @SerialName("last_at") val lastAt: String,
        val uses: Int,
    )

    /** A name they had: [kind] username or name; [value] null for a display name cleared. */
    @Serializable
    class Name(
        val kind: String,
        val value: String? = null,
        val at: String,
        @SerialName("by_staff") val byStaff: Boolean = false,
        val undone: Boolean = false,
    )

    /** An account that may be the same person's, and why. */
    @Serializable
    class Related(val user: UserRef, val why: List<String>)
}

/** A deck in a case. */
@Serializable
class CaseDeck(val id: String, val name: String, val slug: String? = null, val owner: UserRef? = null)

/** A case in the queue: [kind] reports, wave or flag; about a [deck] or a [person]. Times are ISO. */
@Serializable
class CaseItem(
    val id: String,
    val kind: String,
    val lane: String,
    val score: Double,
    val reports: Int,
    val reasons: List<String> = emptyList(),
    val deck: CaseDeck? = null,
    val person: UserRef? = null,
    val summary: String? = null,
    val today: Int? = null,
    val usual: Double? = null,
    @SerialName("opened_at") val openedAt: String,
    @SerialName("resolved_at") val resolvedAt: String? = null,
)

/** One case, with its reports, or what's behind a wave. */
@Serializable
class Case(
    val item: CaseItem,
    val reports: List<Report>,
    val decks: List<CaseDeck>,
    val people: List<UserRef>,
    val reporters: List<UserRef>,
) {
    @Serializable
    class Report(
        val reporter: UserRef,
        val reason: String,
        val law: String? = null,
        val note: String? = null,
        val weight: Double,
        @SerialName("created_at") val createdAt: String,
    )
}

/** A batch of promo codes: [days] of Pro each. Times are ISO. */
@Serializable
class PromoBatch(
    val id: String,
    val note: String? = null,
    val days: Int,
    val codes: Int,
    val used: Int,
    @SerialName("redeem_by") val redeemBy: String? = null,
    @SerialName("created_at") val createdAt: String,
    val by: UserRef? = null,
    val undone: Boolean = false,
)

@Serializable
class PromoBatchCodes(val batch: PromoBatch, val codes: List<Code>) {
    @Serializable
    class Code(
        val code: String,
        @SerialName("used_by") val usedBy: UserRef? = null,
        @SerialName("used_at") val usedAt: String? = null,
        val revoked: Boolean = false,
    )
}
