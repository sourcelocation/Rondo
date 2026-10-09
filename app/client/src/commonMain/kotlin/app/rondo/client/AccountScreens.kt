package app.rondo.client

import app.rondo.client.api.ApiApi
import app.rondo.core.CardSide
import app.rondo.core.Templates
import app.rondo.core.Tree
import app.rondo.core.model.ActionRequest
import app.rondo.core.model.Agent
import app.rondo.core.model.Billing
import app.rondo.core.model.CheckoutRequest
import app.rondo.core.model.ConsentDecision
import app.rondo.core.model.DiscoverItem
import app.rondo.core.model.ErrorCode
import app.rondo.core.model.ProfileUpdate
import app.rondo.core.model.PublicDeck
import app.rondo.core.model.RedeemRequest
import app.rondo.core.model.ResetConfirm
import app.rondo.core.model.ResetRequest
import app.rondo.core.nowMillis
import kotlin.js.JsExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Languages a deck can be in, as BCP 47 codes; UIs show their names in the reader's language. */
@JsExport
val languages: Array<String> = arrayOf(
    "ar", "cs", "da", "de", "el", "en", "es", "fa", "fi", "fr", "he", "hi", "hu", "id", "it", "ja", "ko", "la",
    "nl", "no", "pl", "pt", "ro", "ru", "sv", "th", "tr", "uk", "vi", "zh",
)

@JsExport
class AgentItem internal constructor(val clientId: String, val name: String, val since: String)

/**
 * What Settings' tabs show; who you are is in [AppState]. [provider]: stripe, app-store,
 * google-play, promo or grant. [busy]: what's under way, which can't be asked again until it ends:
 * checkout, portal, redeem, disconnect, export or delete.
 */
@JsExport
class SettingsState internal constructor(
    val signedIn: Boolean,
    val email: String?,
    val activityHidden: Boolean,
    val pro: Boolean,
    val proUntil: String?,
    val provider: String?,
    val renews: Boolean,
    val grading: Int,
    val theme: Int,
    val textSize: Int,
    val agents: Array<AgentItem>,
    /** The hosted MCP server, for agents' settings. */
    val mcpUrl: String,
    /** Deleting the account waits for the code just sent. */
    val confirming: Boolean,
    val busy: String?,
)

/** Preferences, the account, the plan and agents: the screen Settings' tabs share. */
@JsExport
class SettingsScreen internal constructor(override val app: App) : Screen<SettingsState>() {
    override val live get() = false
    private val rondo get() = app.rondo
    private var reauth: CodeFlow? = null
    private var busy: String? = null
    private var awaiting: Job? = null

    /** The subscription and the agents as the server told them: asked when the screen opens. */
    private var billing: Billing? = null
    private var agents: List<Agent>? = null

    override suspend fun load(): SettingsState {
        if (rondo.account.signedIn) {
            if (billing == null) billing = runCatching { online { rondo.net.api.getBilling().ok() } }.getOrNull()
            if (agents == null) agents = runCatching { online { rondo.net.api.listAgents().ok() } }.getOrNull()
        }
        return known()
    }

    /** The state from what's known, without asking the server. */
    private suspend fun known(): SettingsState {
        val s = rondo.store.settings()
        val account = rondo.account
        val me = account.me
        // What the server said is about whoever is signed in: signed out, there's nothing.
        val billing = billing.takeIf { account.signedIn }
        val agents = agents.takeIf { account.signedIn }.orEmpty()
        val pro = billing?.pro ?: (me?.pro == true)
        val proUntil = (billing?.proUntil ?: me?.proUntil)?.toString()
        val connected = agents.map { AgentItem(it.clientId, it.clientName, it.grantedAt.toString()) }
        return SettingsState(
            account.signedIn, account.email, me?.activityHidden == true, pro, proUntil, billing?.provider?.value,
            billing?.renews == true, s.grading, s.theme, s.textSize, connected.toTypedArray(),
            "${rondo.platform.apiUrl}/mcp", reauth != null, busy,
        )
    }

    private fun change(block: suspend () -> Unit) = act {
        block()
        show(known())
    }

    /** Runs [block] as [what], shown as under way until it ends; meanwhile, asking again does nothing. */
    private fun work(what: String, block: suspend () -> Unit) = act {
        if (busy != null) return@act
        busy = what
        try {
            show(known())
            block()
        } finally {
            busy = null
            show(known())
        }
    }

    /**
     * Back from paying: the store tells the server on its own time, so this asks again every few
     * seconds, for half a minute, until Pro shows; then the whole app hears of it.
     */
    fun awaitPro() {
        if (awaiting?.isActive == true) return
        awaiting = scope.launch {
            var tries = 0
            while (billing?.pro != true && tries++ < 10) {
                delay(3_000)
                runCatching { online { rondo.net.api.getBilling().ok() } }.getOrNull()?.let { billing = it }
                show(known())
            }
            if (billing?.pro == true) {
                rondo.account.refresh()
                app.reload()
            }
        }
    }

    fun setName(name: String) = change { rondo.account.setName(name) }

    /** Shows or hides your activity on your public profile. */
    fun setActivityHidden(hidden: Boolean) = change {
        rondo.account.updateProfile(ProfileUpdate(activityHidden = hidden))
    }

    private fun prefer(block: (app.rondo.core.model.Settings) -> app.rondo.core.model.Settings) = change {
        val s = rondo.store.settings()
        rondo.store.save(block(s).copy(v = rondo.store.clock()))
        rondo.syncSoon()
    }

    /** 4 or 2 answer buttons. */
    fun setGrading(buttons: Int) = prefer { it.copy(grading = buttons) }

    /** 0 system, 1 light, 2 dark. */
    fun setTheme(theme: Int) = prefer { it.copy(theme = theme) }

    fun setTextSize(percent: Int) = prefer { it.copy(textSize = percent.coerceIn(50, 200)) }

    /** Starts a Stripe checkout for [plan] (monthly or yearly); [open] goes to its page. */
    fun checkout(plan: String, open: (String) -> Unit) = work("checkout") {
        val request = CheckoutRequest(CheckoutRequest.Plan.entries.first { it.value == plan })
        open(online { rondo.net.api.stripeCheckout(request).ok() }.url)
    }

    fun manage(open: (String) -> Unit) = work("portal") { open(online { rondo.net.api.stripePortal().ok() }.url) }

    /** Redeems a promo code for Pro, from when the Pro there is ends. */
    fun redeem(code: String) = work("redeem") {
        billing = online { rondo.net.api.redeem(RedeemRequest(code.trim())).ok() }
        rondo.account.refresh()
        app.reload()
        app.say(strings.redeemed)
    }

    /** Disconnects an agent: it can't use the account until it's allowed again. */
    fun revokeAgent(clientId: String) = work("disconnect") {
        online { rondo.net.api.revokeAgent(clientId).ok() }
        agents = agents?.filter { it.clientId != clientId }
    }

    fun export() = work("export") {
        online { rondo.net.api.requestExport().ok() }
        app.say(strings.exportSent)
    }

    /** Deletes the account; when the sign-in is too old, sends a code to confirm first. */
    fun deleteAccount() = work("delete") {
        try {
            rondo.account.deleteAccount()
            app.reload()
        } catch (e: SignInError) {
            if (e.code != "reauthenticate") throw e
            reauth = rondo.account.reauthenticate(rondo.account.email ?: throw e)
        }
    }

    fun confirmDelete(code: String) = work("delete") {
        val flow = reauth ?: return@work
        rondo.account.confirm(flow, code)
        reauth = null
        rondo.account.deleteAccount()
        app.reload()
    }
}

/**
 * [step]: email, code, second (the authenticator app or a recovery code), lost (asking to remove a
 * lost second step), lost_code, lost_sent, or done. [error]: why the last try didn't work.
 * [resendAt]: when a new code may be sent.
 */
@JsExport
class SignInState internal constructor(
    val step: String,
    val email: String,
    val busy: Boolean,
    val error: String?,
    val resendAt: Double,
)

/** Signing in or up: an email code, a passkey, or Apple's or Google's ID token. */
@JsExport
class SignInScreen internal constructor(override val app: App) : Screen<SignInState>() {
    override val live get() = false
    private val account get() = app.rondo.account
    private var flow: CodeFlow? = null
    private var busy = false
    private var done = false
    private var error: String? = null
    private var sentAt = 0L

    /** Signed in, but the second step is still due; or the person lost it ([lost] the step of that). */
    private var second: Boolean? = null
    private var lost: String? = null
    private var lostEmail = ""

    override suspend fun load(): SignInState {
        if (second == null) second = account.needsSecondFactor()
        val step = when {
            done -> "done"
            lost != null -> lost!!
            second == true -> "second"
            flow != null -> "code"
            else -> "email"
        }
        val email = if (lost != null) lostEmail else flow?.email ?: account.email.orEmpty()
        return SignInState(step, email, busy, error, (sentAt + RESEND_AFTER).toDouble())
    }

    private fun step(block: suspend () -> Unit) = act {
        busy = true
        error = null
        show(load())
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = app.problem(e)
        } finally {
            busy = false
            show(load())
        }
    }

    private suspend fun signedIn() {
        if (account.needsSecondFactor()) {
            second = true
            return
        }
        second = false
        done = true
        app.reload()
        app.rondo.syncNow()
    }

    /** The second step: a [code] from the authenticator app, or a [recovery] code. */
    fun secondStep(code: String, recovery: Boolean) = step {
        account.secondFactor(code, recovery)
        signedIn()
    }

    /** The second step is lost: asks to remove it (a week later, by email). */
    fun lostSecondStep() = act {
        lost = "lost"
        lostEmail = flow?.email ?: account.email.orEmpty()
        error = null
        show(load())
    }

    /** Sends a code to [email] to confirm removing its account's second step. */
    fun requestReset(email: String) = step {
        online { app.rondo.net.api.requestReset(ResetRequest(email.trim())).ok() }
        lostEmail = email.trim()
        lost = "lost_code"
    }

    fun confirmReset(code: String) = step {
        online { app.rondo.net.api.confirmReset(ResetConfirm(lostEmail, code.trim())).ok() }
        lost = "lost_sent"
    }

    fun sendCode(email: String) = step {
        flow = account.sendCode(email.trim())
        sentAt = nowMillis()
    }

    /** Sends another code to the same address, once [SignInState.resendAt] has passed. */
    fun resend() = step {
        val email = flow?.email ?: return@step
        if (nowMillis() < sentAt + RESEND_AFTER) return@step
        flow = account.sendCode(email)
        sentAt = nowMillis()
    }

    fun confirm(code: String) = step {
        account.confirm(flow ?: return@step, code)
        signedIn()
    }

    fun back() = act {
        flow = null
        lost = null
        error = null
        show(load())
    }

    private companion object {
        const val RESEND_AFTER = 30_000L
    }

    fun passkey() = step {
        val flow = account.passkey()
        account.finishPasskey(flow, app.rondo.platform.passkey(flow.options, register = false))
        signedIn()
    }

    /** An ID token from Apple's or Google's own sign-in ([provider] apple or google). */
    fun idToken(provider: String, token: String, nonce: String?) = step {
        account.idToken(provider, token, nonce)
        signedIn()
    }
}

@JsExport
class ConsentState internal constructor(
    val client: String?,
    val uri: String?,
    val scopes: Array<String>,
    val signedIn: Boolean,
)

/** An agent asking for access through OAuth: allow or deny, then back to it. */
@JsExport
class ConsentScreen internal constructor(override val app: App, private val challenge: String) :
    Screen<ConsentState>() {
    override val live get() = false

    override suspend fun load(): ConsentState {
        if (!app.rondo.account.signedIn) return ConsentState(null, null, emptyArray(), false)
        val c = online { app.rondo.net.api.getConsent(challenge) }.takeIf { it.status != 404 }?.ok()
        return ConsentState(c?.clientName, c?.clientUri, c?.scopes.orEmpty().toTypedArray(), true)
    }

    fun decide(allow: Boolean, open: (String) -> Unit) = act {
        open(online { app.rondo.net.api.decideConsent(challenge, ConsentDecision(allow)).ok() }.url)
    }
}

@JsExport
class PublicItem internal constructor(
    /** Null when it isn't on Discover (seen through an invitation). */
    val slug: String?,
    val deckId: String,
    val name: String,
    val description: String?,
    val language: String?,
    val icon: String?,
    val color: Int,
    val owner: String?,
    /** The author's username, for their profile, and flag. */
    val ownerUsername: String?,
    val ownerFlag: String?,
    val followers: Int,
    val notes: Int,
    /** Already in your decks. */
    val following: Boolean,
)

@JsExport
class DiscoverState internal constructor(
    val items: Array<PublicItem>,
    val more: Boolean,
    val query: String,
    val language: String?,
    /** top (most followed), new or hot (most followed this week). */
    val sort: String,
    val loading: Boolean,
    val offline: Boolean,
)

/** Public decks, by name and language: the most followed, the newest, or what's hot this week. */
@JsExport
class DiscoverScreen internal constructor(override val app: App) : Screen<DiscoverState>() {
    override val live get() = false
    private var query = ""
    private var language: String? = null
    private var sort = ApiApi.SortSearchDiscover.top
    private var page = 0
    private val items = ArrayList<DiscoverItem>()
    private var more = false

    override suspend fun load(): DiscoverState {
        val offline = try {
            if (page == 0) items.clear()
            val found = online { app.rondo.net.api.searchDiscover(query.ifBlank { null }, language, page, sort).ok() }
            items += found.items
            more = found.more
            false
        } catch (e: ApiError) {
            if (e.status != 0) throw e
            true
        }
        val tree = app.rondo.store.tree()
        val shown = items.map { it.item(tree[it.deckId] != null) }
        return DiscoverState(shown.toTypedArray(), more, query, language, sort.value, false, offline)
    }

    fun search(query: String, language: String?) = act {
        this.query = query
        this.language = language?.ifBlank { null }
        page = 0
        show(load())
    }

    /** Orders by [sort]: top, new or hot. */
    fun sortBy(sort: String) = act {
        this.sort = ApiApi.SortSearchDiscover.entries.firstOrNull { it.value == sort } ?: return@act
        page = 0
        show(load())
    }

    fun more() = act {
        page++
        show(load())
    }
}

internal fun DiscoverItem.item(following: Boolean) = PublicItem(
    slug, deckId, name, description, language, icon,
    color ?: 0, ownerName, ownerUsername, ownerFlag, followers, notes, following,
)

@JsExport
class Sample internal constructor(val front: CardSide, val back: CardSide)

/**
 * A deck seen before you have it: from Discover or a share link ([slug]) or an invitation ([token]).
 * [role]: what the invitation gives (1 viewer, 2 editor), or 0 from Discover. [opening]: the deck
 * that's yours to open now; after Follow, Accept or Copy it's set once the deck has synced.
 */
@JsExport
class PreviewState internal constructor(
    val item: PublicItem?,
    val decks: Array<String>,
    val samples: Array<Sample>,
    val signedIn: Boolean,
    val role: Int,
    val busy: Boolean,
    val opening: String?,
)

/** One page for every way to look at a deck before taking it: Discover, a share link, an invitation. */
@JsExport
class PreviewScreen internal constructor(
    override val app: App,
    private val slug: String?,
    private val token: String?,
) : Screen<PreviewState>() {
    override val live get() = false
    private val api get() = app.rondo.net.api
    private var page: PublicDeck? = null
    private var role = 0
    private var fetched = false
    private var busy = false
    private var opening: String? = null

    override suspend fun load(): PreviewState {
        if (!fetched) {
            fetched = true
            if (token != null) {
                online { api.previewInvitation(token) }.takeIf { it.status != 404 }?.ok()?.let {
                    page = it.deck
                    role = it.role
                }
            } else if (slug != null) {
                page = online { api.getPublicDeck(slug) }.takeIf { it.status != 404 }?.ok()
            }
        }
        val signedIn = app.rondo.account.signedIn
        val d = page ?: return PreviewState(null, emptyArray(), emptyArray(), signedIn, role, busy, opening)
        val tree = app.rondo.store.tree()
        val sub = Tree(d.decks)
        val templates = d.samples.templates.orEmpty().associateBy { it.id } + Templates.builtins.associateBy { it.id }
        val notes = d.samples.notes.orEmpty()
        val samples = notes.mapNotNull { n -> templates[n.templateId]?.let { samples(it, n) }?.firstOrNull() }
        val paths = sub.byId.values.map { sub.path(it.id).joinToString(" › ") }.sorted()
        return PreviewState(
            d.item.item(tree[d.item.deckId] != null),
            paths.toTypedArray(),
            samples.toTypedArray(),
            signedIn,
            role,
            busy,
            opening,
        )
    }

    /** Runs [block], which gets a deck of yours, and opens it once it's on this device. */
    private fun take(block: suspend () -> String) = act {
        busy = true
        show(load())
        try {
            opening = block().takeIf { app.rondo.store.tree()[it] != null }
        } finally {
            busy = false
            show(load())
        }
    }

    /** Syncs until the borrowed deck [id] has arrived (a few tries). */
    private suspend fun arrive(id: String): String {
        repeat(5) { if (app.rondo.store.tree()[id] == null) app.rondo.syncNow() }
        return id
    }

    private suspend fun followed(): String {
        val id = page?.item?.deckId ?: throw Refused(ErrorCode.NOT_FOUND)
        if (app.rondo.store.tree()[id] != null) return id
        return arrive(online { api.followDeck(slug ?: throw Refused(ErrorCode.NOT_FOUND)).ok() }.deckId)
    }

    /** Follows a deck on Discover: it's yours to study, and the author's changes keep coming. */
    fun follow() = take { followed() }

    /** Joins the deck an invitation is for. */
    fun accept() = take {
        val invitation = token ?: throw Refused(ErrorCode.NOT_FOUND)
        arrive(online { api.acceptInvitation(invitation).ok() }.deckId)
    }

    /**
     * Staff: takes the deck off Discover for [reason] with a [note] its author sees; [takeDown] also
     * stops sharing it with the people who follow it.
     */
    fun removeFromDiscover(reason: String, note: String?, takeDown: Boolean) = act {
        val id = page?.item?.deckId ?: return@act
        val data = mapOf("take_down" to takeDown.toString())
        val told = note?.ifBlank { null }
        app.doStaff(ActionRequest("deck.remove", deckId = id, reason = reason, note = told, data = data))
        app.say(strings.done)
    }

    /** A copy of your own to change: borrowed just long enough to copy it, unless you already follow it. */
    fun copy() = take {
        val had = page?.item?.deckId?.let { app.rondo.store.tree()[it] } != null
        val id = followed()
        app.rondo.library.copy(id).also { if (!had) leave(app, id) }
    }
}

/** [until] (ISO): when the Pro the code gave ends, once redeemed. */
@JsExport
class RedeemState internal constructor(val code: String, val busy: Boolean, val until: String?, val signedIn: Boolean)

/** Redeeming a promo code for Pro: on the web only (the stores don't allow it in apps). */
@JsExport
class RedeemScreen internal constructor(override val app: App, private var code: String) : Screen<RedeemState>() {
    override val live get() = false
    private var busy = false
    private var until: String? = null

    override suspend fun load() = RedeemState(code, busy, until, app.rondo.account.signedIn)

    fun redeem(code: String) = act {
        this.code = code
        busy = true
        show(load())
        try {
            val billing = online { app.rondo.net.api.redeem(RedeemRequest(code.trim())).ok() }
            until = billing.proUntil?.toString()
            app.rondo.account.refresh()
            app.reload()
        } finally {
            busy = false
            show(load())
        }
    }
}
