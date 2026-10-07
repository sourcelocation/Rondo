package app.rondo.client

import app.rondo.client.api.ApiApi
import app.rondo.core.model.ActionRequest
import app.rondo.core.model.CaseDeck
import app.rondo.core.model.CaseItem
import app.rondo.core.model.PromoBatch
import app.rondo.core.model.StaffAction
import app.rondo.core.model.UndoRequest
import app.rondo.core.model.UserRef
import app.rondo.core.nowMillis
import kotlin.js.JsExport
import kotlin.time.Duration.Companion.days
import kotlinx.datetime.Instant

/** Someone as staff pages name them: [label] is @username, else their name. */
@JsExport
class PersonRef internal constructor(val id: String, val label: String, val username: String?)

internal fun UserRef.person() = PersonRef(id, username?.let { "@$it" } ?: name ?: strings.someone, username)

/** One entry of the staff log, in words. [at], [until] and [undoneAt] are ISO times. */
@JsExport
class LogItem internal constructor(
    val id: String,
    val kind: String,
    val summary: String,
    val actor: PersonRef?,
    val person: PersonRef?,
    val deckId: String?,
    val deckName: String?,
    val reason: String?,
    val note: String?,
    val until: String?,
    val at: String,
    val undone: Boolean,
    val undoneBy: PersonRef?,
    val undoReason: String?,
    val undoable: Boolean,
)

internal fun StaffAction.item(): LogItem {
    val who = user?.person()
    return LogItem(
        id, kind, strings.action(kind, data, who?.label ?: strings.someone, deckName), actor?.person(), who, deckId,
        deckName, reason?.let(strings::reason), note, until?.toString(), createdAt.toString(), undoneAt != null,
        undoneBy?.person(), undoReason, undoable,
    )
}

/** [days] from now, or null for none (for good, no end). */
internal fun inDays(days: Int): Instant? =
    if (days > 0) Instant.fromEpochMilliseconds(nowMillis() + days.days.inWholeMilliseconds) else null

/** Runs a staff action: every change staff make goes through this one call, and the log. */
internal suspend fun App.doStaff(request: ActionRequest): StaffAction = online { rondo.net.api.doAction(request).ok() }

/** [removable]: the roles you may take from them. */
@JsExport
class TeamMemberItem internal constructor(val person: PersonRef, val roles: Array<String>, val removable: Array<String>)

/** [grantable]: the roles you may give. */
@JsExport
class TeamState internal constructor(val members: Array<TeamMemberItem>, val grantable: Array<String>)

/** The owner and everyone with a role. */
@JsExport
class TeamScreen internal constructor(override val app: App) : Screen<TeamState>() {
    override val live get() = false

    override suspend fun load(): TeamState {
        val permissions = app.rondo.account.me?.permissions.orEmpty()
        val grantable = GRANTABLE.filter { "roles.$it" in permissions }
        val members = online { app.rondo.net.api.listTeam().ok() }.map { m ->
            val removable = m.roles.filter { it in grantable && "owner" !in m.roles }
            TeamMemberItem(m.user.person(), m.roles.toTypedArray(), removable.toTypedArray())
        }
        return TeamState(members.toTypedArray(), grantable.toTypedArray())
    }

    /** Gives [userId] a [role]. */
    fun grant(userId: String, role: String) = act {
        app.doStaff(ActionRequest("role.grant", userId = userId, data = mapOf("role" to role)))
        show(load())
    }

    /** Takes [role] from [userId]. */
    fun revoke(userId: String, role: String) = act {
        app.doStaff(ActionRequest("role.revoke", userId = userId, data = mapOf("role" to role)))
        show(load())
    }

    private companion object {
        val GRANTABLE = listOf("admin", "moderator")
    }
}

@JsExport
class StaffLogState internal constructor(val items: Array<LogItem>, val more: Boolean)

/** The staff log, newest first: everyone's, or about one person ([user]), deck or actor. */
@JsExport
class StaffLogScreen internal constructor(
    override val app: App,
    private val actor: String?,
    private val user: String?,
    private val deck: String?,
) : Screen<StaffLogState>() {
    override val live get() = false
    private val items = ArrayList<StaffAction>()
    private var more = false

    override suspend fun load(): StaffLogState {
        if (items.isEmpty()) fetch(null)
        return StaffLogState(items.map { it.item() }.toTypedArray(), more)
    }

    private suspend fun fetch(before: String?) {
        val page = online { app.rondo.net.api.listActions(actor, user, deck, null, before).ok() }
        items += page.items
        more = page.more
    }

    /** The next page. */
    fun more() = act {
        fetch(items.lastOrNull()?.id)
        show(load())
    }

    /** Undoes an entry, saying why. */
    fun undo(id: String, reason: String) = act {
        val undone = online { app.rondo.net.api.undoAction(id, UndoRequest(reason)).ok() }
        val at = items.indexOfFirst { it.id == id }
        if (at >= 0) items[at] = undone
        show(load())
    }
}

/** One entry of the log, which can be shared as a link. */
@JsExport
class StaffActionScreen internal constructor(override val app: App, private val id: String) : Screen<LogItem>() {
    override val live get() = false

    override suspend fun load(): LogItem = online { app.rondo.net.api.getAction(id).ok() }.item()

    fun undo(reason: String) = act {
        online { app.rondo.net.api.undoAction(id, UndoRequest(reason)).ok() }
        show(load())
    }
}

/** Someone in staff search results. [restrictedUntil] and [bannedUntil] are ISO, when they apply. */
@JsExport
class StaffPersonItem internal constructor(
    val person: PersonRef,
    val joined: String,
    val roles: Array<String>,
    val restrictedUntil: String?,
    val bannedUntil: String?,
)

@JsExport
class StaffSearchState internal constructor(val query: String, val by: String, val items: Array<StaffPersonItem>)

/** Finds people: by username, name or id; by email (with users.email); by address (users.network). */
@JsExport
class StaffSearchScreen internal constructor(override val app: App) : Screen<StaffSearchState>() {
    override val live get() = false
    private var query = ""
    private var by = "name"
    private var items = emptyList<StaffPersonItem>()

    override suspend fun load() = StaffSearchState(query, by, items.toTypedArray())

    /** Searches [by] name, email or ip. */
    fun search(query: String, by: String) = act {
        this.query = query
        this.by = by
        items = if (query.isBlank()) {
            emptyList()
        } else {
            val api = app.rondo.net.api
            val found = online {
                when (by) {
                    "email" -> api.searchUsers(email = query.trim())
                    "ip" -> api.searchUsers(ip = query.trim())
                    else -> api.searchUsers(q = query.trim())
                }.ok()
            }
            found.map {
                val roles = it.roles.toTypedArray()
                val restricted = it.restrictedUntil?.toString()
                StaffPersonItem(it.user.person(), it.joined.toString(), roles, restricted, it.bannedUntil?.toString())
            }
        }
        show(load())
    }
}

@JsExport
class AddressItem internal constructor(val ip: String, val first: String, val last: String, val uses: Int)

/** A name someone had: [kind] username or name; [value] null for a display name cleared. */
@JsExport
class NameItem internal constructor(
    val kind: String,
    val value: String?,
    val at: String,
    val byStaff: Boolean,
    val undone: Boolean,
)

/** An account that may be the same person's, and why: address, email or name. */
@JsExport
class RelatedItem internal constructor(val person: PersonRef, val why: Array<String>)

/**
 * Someone as staff see them. [suggestedKind] and [suggestedDays] are the ladder's next step. The
 * private details are null without the permissions for them. [grantable] and [removable] are roles.
 */
@JsExport
class StaffUserState internal constructor(
    val person: PersonRef,
    val joined: String,
    val pro: Boolean,
    val roles: Array<String>,
    val restrictedUntil: String?,
    val bannedUntil: String?,
    val recent: Int,
    val suggestedKind: String,
    val suggestedDays: Int?,
    val email: String?,
    val emailVerified: Boolean,
    val signIn: Array<String>?,
    val addresses: Array<AddressItem>?,
    val names: Array<NameItem>?,
    val related: Array<RelatedItem>?,
    val billing: String?,
    val grantable: Array<String>,
    val removable: Array<String>,
)

/** One person's staff panel: their standing and history, the ladder, and what staff can do. */
@JsExport
class StaffUserScreen internal constructor(override val app: App, private val userId: String) :
    Screen<StaffUserState>() {
    override val live get() = false

    override suspend fun load(): StaffUserState {
        val u = online { app.rondo.net.api.getStaffUser(userId).ok() }
        val permissions = app.rondo.account.me?.permissions.orEmpty()
        val grantable = listOf("admin", "moderator").filter { "roles.$it" in permissions && it !in u.roles }
        val removable = u.roles.filter { "roles.$it" in permissions }
        val billing = u.billing?.let { b ->
            val parts = listOfNotNull(strings.pro, b.provider?.value, b.proUntil?.toString())
            if (b.pro) parts.joinToString(" · ") else strings.free
        }
        val addresses = u.ips?.map { AddressItem(it.ip, it.firstAt.toString(), it.lastAt.toString(), it.uses) }
        val names = u.names?.map { NameItem(it.kind, it.value, it.at.toString(), it.byStaff, it.undone) }
        return StaffUserState(
            person = u.user.person(),
            joined = u.joined.toString(),
            pro = u.pro,
            roles = u.roles.toTypedArray(),
            restrictedUntil = u.restrictedUntil?.toString(),
            bannedUntil = u.bannedUntil?.toString(),
            recent = u.recent,
            suggestedKind = u.suggestedKind,
            suggestedDays = u.suggestedDays,
            email = u.email,
            emailVerified = u.emailVerified == true,
            signIn = u.signIn?.toTypedArray(),
            addresses = addresses?.toTypedArray(),
            names = names?.toTypedArray(),
            related = u.related?.map { RelatedItem(it.user.person(), it.why.toTypedArray()) }?.toTypedArray(),
            billing = billing,
            grantable = grantable.toTypedArray(),
            removable = removable.toTypedArray(),
        )
    }

    /**
     * Warns, restricts, bans, lifts or resets the name of this person ([kind]: user.warn,
     * user.restrict, user.ban, user.lift, user.rename), for [reason] with a [note] they see.
     * [days]: how long a restriction or ban lasts; 0 for good.
     */
    fun moderate(kind: String, reason: String?, note: String?, days: Int) = act {
        val until = inDays(days)
        app.doStaff(ActionRequest(kind, userId = userId, reason = reason, note = note?.ifBlank { null }, until = until))
        app.say(strings.done)
        show(load())
    }

    fun grant(role: String) = act {
        app.doStaff(ActionRequest("role.grant", userId = userId, data = mapOf("role" to role)))
        show(load())
    }

    fun revoke(role: String) = act {
        app.doStaff(ActionRequest("role.revoke", userId = userId, data = mapOf("role" to role)))
        show(load())
    }

    /** Gives them Pro for [days], without a code or a store. */
    fun grantPro(days: Int) = act {
        val until = inDays(days)
        app.doStaff(ActionRequest("pro.grant", userId = userId, until = until))
        app.say(strings.done)
        show(load())
    }

    /** Restricts the addresses they were seen at, for [days] (0: for good). */
    fun restrictAddress(ip: String, days: Int) = act {
        val until = inDays(days)
        app.doStaff(ActionRequest("network.restrict", until = until, data = mapOf("range" to ip)))
        app.say(strings.done)
    }
}

/** A deck in a case: its name, Discover address when listed, and author. */
@JsExport
class CaseDeckItem internal constructor(val id: String, val name: String, val slug: String?, val owner: PersonRef?)

internal fun CaseDeck.item() = CaseDeckItem(id, name, slug, owner?.person())

/**
 * A case in the queue. [kind]: reports, wave or flag. [summary]: what a wave measured or the rule
 * that flagged it, in words. [deck] or [person]: what it's about.
 */
@JsExport
class CaseItemView internal constructor(
    val id: String,
    val kind: String,
    val lane: String,
    val score: Double,
    val reports: Int,
    val reasons: Array<String>,
    val deck: CaseDeckItem?,
    val person: PersonRef?,
    val summary: String?,
    val opened: String,
    val resolved: Boolean,
)

internal fun CaseItem.view(): CaseItemView {
    val why = reasons.map(strings::reportReason).toTypedArray()
    val said = strings.caseSummary(kind, summary, today, usual?.toDouble())
    val opened = openedAt.toString()
    val about = deck?.item()
    val who = person?.person()
    return CaseItemView(id, kind, lane, score.toDouble(), reports, why, about, who, said, opened, resolvedAt != null)
}

@JsExport
class CasesState internal constructor(val lane: String?, val items: Array<CaseItemView>)

/** The queue: open cases, the most serious lanes first. */
@JsExport
class CasesScreen internal constructor(override val app: App) : Screen<CasesState>() {
    override val live get() = false
    private var lane: String? = null

    override suspend fun load(): CasesState {
        val filter = lane?.let { l -> ApiApi.LaneListCases.entries.firstOrNull { it.value == l } }
        val items = online { app.rondo.net.api.listCases(filter, null).ok() }.map { it.view() }
        return CasesState(lane, items.toTypedArray())
    }

    /** Shows one lane, or all ([lane] null). */
    fun show(lane: String?) = act {
        this.lane = lane
        show(load())
    }
}

/** One report: who, why (with the law for illegal content), what they wrote, and its weight. */
@JsExport
class ReportView internal constructor(
    val reporter: PersonRef,
    val reason: String,
    val law: String?,
    val note: String?,
    val weight: Double,
    val at: String,
)

@JsExport
class CaseState internal constructor(
    val item: CaseItemView,
    val reports: Array<ReportView>,
    val decks: Array<CaseDeckItem>,
    val people: Array<PersonRef>,
    val reporters: Array<PersonRef>,
)

/**
 * One case: its reports, or what's behind a wave. Acting from here resolves it; so does dismissing.
 * A wave's decks and people can be acted on together.
 */
@JsExport
class CaseScreen internal constructor(override val app: App, private val id: String) : Screen<CaseState>() {
    override val live get() = false

    override suspend fun load(): CaseState {
        val c = online { app.rondo.net.api.getCase(id).ok() }
        val reports = c.reports.map {
            val why = strings.reportReason(it.reason)
            val law = it.law?.let(strings::law)
            ReportView(it.reporter.person(), why, law, it.note, it.weight.toDouble(), it.createdAt.toString())
        }
        val decks = c.decks.map { it.item() }.toTypedArray()
        val people = c.people.map { it.person() }.toTypedArray()
        val reporters = c.reporters.map { it.person() }.toTypedArray()
        return CaseState(c.item.view(), reports.toTypedArray(), decks, people, reporters)
    }

    /** Nothing to act on: closes the case, and its reports count against their reporters next time. */
    fun dismiss() = act {
        app.doStaff(ActionRequest("case.dismiss", caseId = id))
        app.say(strings.done)
        show(load())
    }

    /** Takes [decks] off Discover, with this case. */
    fun removeDecks(decks: Array<String>, reason: String, note: String?, takeDown: Boolean) = act {
        val removal = ActionRequest(
            "deck.remove",
            reason = reason,
            note = note?.ifBlank { null },
            data = mapOf("take_down" to takeDown.toString()),
            caseId = id,
        )
        for (deck in decks) app.doStaff(removal.copy(deckId = deck))
        app.say(strings.done)
        show(load())
    }

    /** Warns, restricts or bans [people] ([kind] as for a person's panel), with this case. */
    fun moderate(people: Array<String>, kind: String, reason: String?, note: String?, days: Int) = act {
        val until = inDays(days)
        val told = note?.ifBlank { null }
        for (person in people) {
            app.doStaff(ActionRequest(kind, userId = person, reason = reason, note = told, until = until, caseId = id))
        }
        app.say(strings.done)
        show(load())
    }
}

/** A batch of promo codes: [days] of Pro each; [redeemBy] ISO, when they stop working. */
@JsExport
class BatchItem internal constructor(
    val id: String,
    val note: String?,
    val days: Int,
    val codes: Int,
    val used: Int,
    val redeemBy: String?,
    val created: String,
    val by: PersonRef?,
    val undone: Boolean,
)

internal fun PromoBatch.item() =
    BatchItem(id, note, days, codes, used, redeemBy?.toString(), createdAt.toString(), by?.person(), undone)

@JsExport
class PromoState internal constructor(val batches: Array<BatchItem>)

/** Promo codes: the batches made so far, and making another. */
@JsExport
class PromoScreen internal constructor(override val app: App) : Screen<PromoState>() {
    override val live get() = false

    override suspend fun load(): PromoState {
        val batches = online { app.rondo.net.api.listPromoBatches().ok() }
        return PromoState(batches.map { it.item() }.toTypedArray())
    }

    /**
     * Makes [count] codes for [days] of Pro each, usable for [redeemDays] (0: no end), with a [note]
     * for staff. [open] gets the new batch's id.
     */
    fun create(count: Int, days: Int, redeemDays: Int, note: String?, open: (String) -> Unit) = act {
        val until = inDays(redeemDays)
        val data = mapOf("count" to count.toString(), "days" to days.toString())
        val made = app.doStaff(ActionRequest("promo.batch", note = note?.ifBlank { null }, until = until, data = data))
        open(made.id)
    }
}

/** A code of a batch: unused, used by someone (and when), or revoked. */
@JsExport
class CodeItem internal constructor(val code: String, val usedBy: PersonRef?, val usedAt: String?, val revoked: Boolean)

@JsExport
class BatchState internal constructor(val batch: BatchItem, val codes: Array<CodeItem>, val unused: String)

/** One batch and its codes. */
@JsExport
class BatchScreen internal constructor(override val app: App, private val id: String) : Screen<BatchState>() {
    override val live get() = false

    override suspend fun load(): BatchState {
        val b = online { app.rondo.net.api.getPromoBatch(id).ok() }
        val codes = b.codes.map { CodeItem(it.code, it.usedBy?.person(), it.usedAt?.toString(), it.revoked) }
        val unused = codes.filter { it.usedBy == null && !it.revoked }.joinToString("\n") { it.code }
        return BatchState(b.batch.item(), codes.toTypedArray(), unused)
    }

    /** Revokes one code; Pro from it ends. */
    fun revoke(code: String) = act {
        app.doStaff(ActionRequest("promo.revoke", data = mapOf("code" to code)))
        show(load())
    }
}
