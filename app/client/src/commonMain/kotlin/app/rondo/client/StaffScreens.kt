package app.rondo.client

import app.rondo.client.services.CaseDeck
import app.rondo.client.services.CaseItem
import app.rondo.client.services.PromoBatch
import app.rondo.client.services.StaffAction
import app.rondo.client.services.UserRef
import kotlin.js.JsExport

/** Someone as staff pages name them: [label] is @username, else their name. */
@JsExport
class PersonRef internal constructor(val id: String, val label: String, val username: String?) {
    internal constructor(u: UserRef) : this(u.id, u.username?.let { "@$it" } ?: u.name ?: strings.someone, u.username)
}

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
) {
    internal constructor(a: StaffAction) : this(
        a.id, a.kind, strings.action(a.kind, a.data, a.user?.let(::PersonRef)?.label ?: strings.someone, a.deckName),
        a.actor?.let(
            ::PersonRef,
        ),
        a.user?.let(::PersonRef), a.deckId, a.deckName, a.reason?.let(strings::reason), a.note,
        a.until, a.createdAt, a.undoneAt != null, a.undoneBy?.let(::PersonRef), a.undoReason, a.undoable,
    )
}

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
        val members = app.rondo.staff.team().map { m ->
            val removable = m.roles.filter { it in grantable && "owner" !in m.roles }
            TeamMemberItem(PersonRef(m.user), m.roles.toTypedArray(), removable.toTypedArray())
        }
        return TeamState(members.toTypedArray(), grantable.toTypedArray())
    }

    /** Gives [userId] a [role]. */
    fun grant(userId: String, role: String) = act {
        app.rondo.staff.act("role.grant", userId = userId, data = mapOf("role" to role))
        show(load())
    }

    /** Takes [role] from [userId]. */
    fun revoke(userId: String, role: String) = act {
        app.rondo.staff.act("role.revoke", userId = userId, data = mapOf("role" to role))
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
        return StaffLogState(items.map(::LogItem).toTypedArray(), more)
    }

    private suspend fun fetch(before: String?) {
        val page = app.rondo.staff.log(actor, user, deck, before)
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
        val undone = app.rondo.staff.undo(id, reason)
        val at = items.indexOfFirst { it.id == id }
        if (at >= 0) items[at] = undone
        show(load())
    }
}

/** One entry of the log, which can be shared as a link. */
@JsExport
class StaffActionScreen internal constructor(override val app: App, private val id: String) : Screen<LogItem>() {
    override val live get() = false

    override suspend fun load(): LogItem = LogItem(app.rondo.staff.action(id))

    fun undo(reason: String) = act {
        app.rondo.staff.undo(id, reason)
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
            app.rondo.staff.search(query.trim(), by).map {
                StaffPersonItem(
                    PersonRef(it.user),
                    it.joined,
                    it.roles.toTypedArray(),
                    it.restrictedUntil,
                    it.bannedUntil,
                )
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
        val u = app.rondo.staff.user(userId)
        val permissions = app.rondo.account.me?.permissions.orEmpty()
        val grantable = listOf("admin", "moderator").filter { "roles.$it" in permissions && it !in u.roles }
        val removable = u.roles.filter { "roles.$it" in permissions }
        val billing = u.billing?.let { b ->
            val parts = listOfNotNull(strings.pro, b.provider, b.proUntil)
            if (b.pro) parts.joinToString(" · ") else strings.free
        }
        val addresses = u.ips?.map { AddressItem(it.ip, it.firstAt, it.lastAt, it.uses) }
        val names = u.names?.map { NameItem(it.kind, it.value, it.at, it.byStaff, it.undone) }
        return StaffUserState(
            person = PersonRef(u.user),
            joined = u.joined,
            pro = u.pro,
            roles = u.roles.toTypedArray(),
            restrictedUntil = u.restrictedUntil,
            bannedUntil = u.bannedUntil,
            recent = u.recent,
            suggestedKind = u.suggestedKind,
            suggestedDays = u.suggestedDays,
            email = u.email,
            emailVerified = u.emailVerified == true,
            signIn = u.signIn?.toTypedArray(),
            addresses = addresses?.toTypedArray(),
            names = names?.toTypedArray(),
            related = u.related?.map { RelatedItem(PersonRef(it.user), it.why.toTypedArray()) }?.toTypedArray(),
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
        app.rondo.staff.act(kind, userId = userId, reason = reason, note = note, days = days)
        app.say(strings.done)
        show(load())
    }

    fun grant(role: String) = act {
        app.rondo.staff.act("role.grant", userId = userId, data = mapOf("role" to role))
        show(load())
    }

    fun revoke(role: String) = act {
        app.rondo.staff.act("role.revoke", userId = userId, data = mapOf("role" to role))
        show(load())
    }

    /** Gives them Pro for [days], without a code or a store. */
    fun grantPro(days: Int) = act {
        app.rondo.staff.act("pro.grant", userId = userId, days = days)
        app.say(strings.done)
        show(load())
    }

    /** Restricts the addresses they were seen at, for [days] (0: for good). */
    fun restrictAddress(ip: String, days: Int) = act {
        app.rondo.staff.act("network.restrict", days = days, data = mapOf("range" to ip))
        app.say(strings.done)
    }
}

/** A deck in a case: its name, Discover address when listed, and author. */
@JsExport
class CaseDeckItem internal constructor(val id: String, val name: String, val slug: String?, val owner: PersonRef?) {
    internal constructor(d: CaseDeck) : this(d.id, d.name, d.slug, d.owner?.let(::PersonRef))
}

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
) {
    internal constructor(c: CaseItem) : this(
        c.id, c.kind, c.lane, c.score, c.reports, c.reasons.map(strings::reportReason).toTypedArray(),
        c.deck?.let(
            ::CaseDeckItem,
        ),
        c.person?.let(::PersonRef), strings.caseSummary(c.kind, c.summary, c.today, c.usual),
        c.openedAt, c.resolvedAt != null,
    )
}

@JsExport
class CasesState internal constructor(val lane: String?, val items: Array<CaseItemView>)

/** The queue: open cases, the most serious lanes first. */
@JsExport
class CasesScreen internal constructor(override val app: App) : Screen<CasesState>() {
    override val live get() = false
    private var lane: String? = null

    override suspend fun load(): CasesState {
        val items = app.rondo.staff.cases(lane).map(::CaseItemView)
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
        val c = app.rondo.staff.case(id)
        val reports = c.reports.map {
            val why = strings.reportReason(it.reason)
            ReportView(PersonRef(it.reporter), why, it.law?.let(strings::law), it.note, it.weight, it.createdAt)
        }
        val decks = c.decks.map(::CaseDeckItem).toTypedArray()
        val people = c.people.map(::PersonRef).toTypedArray()
        val reporters = c.reporters.map(::PersonRef).toTypedArray()
        return CaseState(CaseItemView(c.item), reports.toTypedArray(), decks, people, reporters)
    }

    /** Nothing to act on: closes the case, and its reports count against their reporters next time. */
    fun dismiss() = act {
        app.rondo.staff.act("case.dismiss", caseId = id)
        app.say(strings.done)
        show(load())
    }

    /** Takes [decks] off Discover, with this case. */
    fun removeDecks(decks: Array<String>, reason: String, note: String?, takeDown: Boolean) = act {
        val data = mapOf("take_down" to takeDown.toString())
        for (deck in decks) {
            app.rondo.staff.act(
                "deck.remove",
                deckId = deck,
                reason = reason,
                note = note,
                data = data,
                caseId = id,
            )
        }
        app.say(strings.done)
        show(load())
    }

    /** Warns, restricts or bans [people] ([kind] as for a person's panel), with this case. */
    fun moderate(people: Array<String>, kind: String, reason: String?, note: String?, days: Int) = act {
        for (person in people) {
            app.rondo.staff.act(kind, userId = person, reason = reason, note = note, days = days, caseId = id)
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
) {
    internal constructor(b: PromoBatch) : this(
        b.id, b.note, b.days, b.codes, b.used, b.redeemBy, b.createdAt, b.by?.let(::PersonRef), b.undone,
    )
}

@JsExport
class PromoState internal constructor(val batches: Array<BatchItem>)

/** Promo codes: the batches made so far, and making another. */
@JsExport
class PromoScreen internal constructor(override val app: App) : Screen<PromoState>() {
    override val live get() = false

    override suspend fun load(): PromoState = PromoState(app.rondo.staff.batches().map(::BatchItem).toTypedArray())

    /**
     * Makes [count] codes for [days] of Pro each, usable for [redeemDays] (0: no end), with a [note]
     * for staff. [open] gets the new batch's id.
     */
    fun create(count: Int, days: Int, redeemDays: Int, note: String?, open: (String) -> Unit) = act {
        val data = mapOf("count" to count.toString(), "days" to days.toString())
        val made = app.rondo.staff.act("promo.batch", note = note, days = redeemDays, data = data)
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
        val b = app.rondo.staff.batch(id)
        val codes = b.codes.map { CodeItem(it.code, it.usedBy?.let(::PersonRef), it.usedAt, it.revoked) }
        val unused = codes.filter { it.usedBy == null && !it.revoked }.joinToString("\n") { it.code }
        return BatchState(BatchItem(b.batch), codes.toTypedArray(), unused)
    }

    /** Revokes one code; Pro from it ends. */
    fun revoke(code: String) = act {
        app.rondo.staff.act("promo.revoke", data = mapOf("code" to code))
        show(load())
    }
}
