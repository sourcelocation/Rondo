package app.rondo.client

import app.rondo.core.model.Agent
import app.rondo.core.model.Billing
import app.rondo.core.model.ProfileUpdate
import kotlin.js.JsExport
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@JsExport
class AgentItem internal constructor(val clientId: String, val name: String, val since: String)

/**
 * What Settings' tabs show; who you are is in [AppState]. [provider]: stripe, app-store,
 * google-play, promo or grant. [busy]: what's under way, which can't be asked again until it ends:
 * checkout (back from paying, until Pro shows), portal, redeem, disconnect, export, delete or server.
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
    /** The server signing in reaches, changed only while signed out; null where it can't be chosen. */
    val server: String?,
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
            "${rondo.net.apiUrl}/mcp", rondo.net.apiUrl.takeIf { rondo.platform.serverChoice }, reauth != null, busy,
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
     * Back from paying. The store tells the server on its own time, so the checkout stays under way
     * while this asks again every few seconds, for half a minute. Once Pro shows, the account is read
     * again, and the server's welcome to Pro comes with it as a notification.
     */
    fun awaitPro() {
        if (awaiting?.isActive == true) return
        awaiting = scope.launch {
            busy = "checkout"
            try {
                var tries = 0
                while (billing?.pro != true && tries++ < 10) {
                    show(known())
                    delay(3_000)
                    runCatching { online { rondo.net.api.getBilling().ok() } }.getOrNull()?.let { billing = it }
                }
                if (billing?.pro == true) {
                    rondo.account.refresh()
                    app.reload()
                }
            } finally {
                busy = null
                show(known())
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

    /** Signs in to the Rondo server at [address] from now on; blank is the app's own. Only signed out. */
    fun setServer(address: String) = work("server") { rondo.net.useServer(address) }

    /** Starts a Stripe checkout for [plan] (monthly or yearly); [open] goes to its page. */
    fun checkout(plan: String, open: (String) -> Unit) = work("checkout") { open(app.checkoutPage(plan)) }

    fun manage(open: (String) -> Unit) = work("portal") { open(app.portalPage()) }

    /**
     * Redeems a promo code for Pro, from when the Pro there is ends. Pro that starts is welcomed by
     * the server's own notification; only more of it is said here.
     */
    fun redeem(code: String) = work("redeem") {
        val had = billing?.pro ?: (rondo.account.me?.pro == true)
        billing = app.redeemPromo(code)
        if (had) app.say(strings.redeemed)
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
