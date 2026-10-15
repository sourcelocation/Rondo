package app.rondo.client

import app.rondo.client.net.ApiError
import app.rondo.client.net.SignInError
import app.rondo.client.services.Subscription
import app.rondo.core.ErrorCode
import app.rondo.core.Filter
import app.rondo.core.Refused
import io.ktor.http.parseQueryString
import io.ktor.util.toMap
import kotlin.js.JsExport
import kotlin.time.Clock
import kotlinx.coroutines.launch

/**
 * A report of a deck or a person ([kind] deck or person, [id]) for [reason]. Illegal content needs
 * the [law], a [note] saying why, and [goodFaith].
 */
@JsExport
class Report(
    val kind: String,
    val id: String,
    val reason: String,
    val law: String?,
    val note: String?,
    val goodFaith: Boolean,
)

/**
 * A notification the server wrote, to show once: as a dialog that waits for the person ([modal]),
 * or briefly. [link] is a path into the app on this server (`/app/…`), or a page of the docs.
 */
@JsExport
class NotificationItem internal constructor(
    val id: String,
    val kind: String,
    val modal: Boolean,
    val title: String,
    val body: String,
    val linkLabel: String?,
    val link: String?,
)

/**
 * Why syncing stopped: [label] says it in a few words, for the sync line, and [message] in full.
 * [signIn]: signing in again resumes it; otherwise trying again might. [needsYou]: it stays stopped
 * until the person does something, unlike being offline or a sync that didn't work, which retry.
 */
@JsExport
class SyncProblem internal constructor(
    val label: String,
    val message: String,
    val signIn: Boolean,
    val needsYou: Boolean,
)

/**
 * What the whole app shows around its screens. [sync] is null, or why syncing stopped.
 * [permissions] are the staff tools usable now; [staff] says whether to show them (a staff member can
 * hide them, for screenshots); [staffLocked]: the person has a role, but this session didn't sign in
 * with a passkey. [notification] is the next one to show.
 */
@JsExport
class AppState internal constructor(
    val signedIn: Boolean,
    val name: String?,
    val email: String?,
    val pro: Boolean,
    val syncing: Boolean,
    val sync: SyncProblem?,
    val syncedAt: Double?,
    val issues: Int,
    val grading: Int,
    val theme: Int,
    val textSize: Int,
    val roles: Array<String>,
    val permissions: Array<String>,
    val staffLocked: Boolean,
    val staff: Boolean,
    val notification: NotificationItem?,
    /** Show the profile setup: once after signing in, and when something needs it. */
    val setup: Boolean,
    val username: String?,
    val flag: String?,
)

/** Rondo for a UI: the app-wide state, notices, and every screen. */
@JsExport
class App internal constructor(internal val rondo: Rondo) : Screen<AppState>() {
    override val app: App get() = this
    private val noticed = ArrayList<(String) -> Unit>()

    init {
        scope.launch { rondo.sync.collect { reload() } }
    }

    /** Notifications shown on this device, until the server hears they were. */
    private val shown = HashSet<String>()

    override suspend fun load(): AppState {
        val s = rondo.library.settings()
        val sync = rondo.sync.value
        val me = rondo.account.me
        val issues = rondo.syncer.issues().size
        val roles = me?.roles.orEmpty()
        val permissions = me?.permissions.orEmpty()
        val next = me?.notifications.orEmpty().firstOrNull { it.id !in shown }?.let {
            NotificationItem(it.id, it.kind, it.presentation == "modal", it.title, it.body, it.linkLabel, it.linkUrl)
        }
        val unconfirmed = me != null && me.profileConfirmed == false
        val setup = unconfirmed && (setupWanted || rondo.platform.read(SETUP_ASKED) == null)
        return AppState(
            rondo.account.signedIn, me?.name, rondo.account.email, me?.pro == true, sync.busy,
            sync.code?.let(::syncProblem), sync.at?.toDouble(), issues, s.grading, s.theme, s.textSize,
            roles.toTypedArray(), permissions.toTypedArray(), me?.staffLocked == true,
            rondo.platform.read(STAFF_HIDDEN) == null, next, setup || setupOpen, me?.username, me?.flag,
        )
    }

    /** Something needs the profile, or the person asked to edit it. */
    private var setupWanted = false
    private var setupOpen = false

    /** Opens the profile setup: to edit the profile, or because something needs it. */
    fun editProfile() {
        setupOpen = true
        reload()
    }

    internal fun setupDone() {
        setupWanted = false
        setupOpen = false
        rondo.platform.write(SETUP_ASKED, "1")
        reload()
    }

    internal fun skipSetup() = setupDone()

    fun profileSetup() = ProfileSetupScreen(this)

    /** Someone's public profile; a [year] of their activity, or the latest. */
    fun profile(username: String, year: Int?) = ProfileScreen(this, username, year)

    /** A notification was shown (a dialog closed, a toast appeared): no device shows it again. */
    fun seen(id: String) {
        shown += id
        reload()
        act { rondo.account.seen(id) }
    }

    /** Reads the account again: when the app comes back to the front. */
    fun refresh() = act {
        if (!rondo.account.signedIn) return@act
        rondo.account.refresh()
        reload()
    }

    /** Signs in again with a passkey, which staff tools need. */
    fun confirmPasskey() = act {
        rondo.account.confirmWithPasskey()
        reload()
    }

    /** Shows or hides staff tools on this device; hidden, the app looks like anyone's. */
    fun showStaff(shown: Boolean) {
        rondo.platform.write(STAFF_HIDDEN, if (shown) null else "1")
        reload()
    }

    /** Staff tools: the team, the log, one entry of it. */
    fun team() = TeamScreen(this)

    fun staffLog(actor: String?, user: String?, deck: String?) = StaffLogScreen(this, actor, user, deck)

    fun staffAction(id: String) = StaffActionScreen(this, id)

    fun staffSearch() = StaffSearchScreen(this)

    /** Someone's staff panel. */
    fun staffUser(id: String) = StaffUserScreen(this, id)

    fun security() = SecurityScreen(this)

    fun reminders() = RemindersScreen(this)

    fun cases() = CasesScreen(this)

    fun promo() = PromoScreen(this)

    fun batch(id: String) = BatchScreen(this, id)

    /** Redeeming a promo code, typed or from a link ([code]). */
    fun redeem(code: String?) = RedeemScreen(this, code.orEmpty())

    fun case(id: String) = CaseScreen(this, id)

    /** Reports a deck or a person: see [Report]. [done] runs once it's received. */
    fun report(report: Report, done: () -> Unit) = act {
        rondo.discover.report(report.kind, report.id, report.reason, report.law, report.note, report.goodFaith)
        say(strings.reportSent)
        done()
    }

    private fun syncProblem(code: String) = when (code) {
        "offline" -> SyncProblem(strings.offlineTitle, strings.offline, signIn = false, needsYou = false)

        "signed_out" -> SyncProblem(strings.signedOutTitle, strings.signedOutSync, signIn = true, needsYou = true)

        "outdated" -> SyncProblem(strings.outdatedTitle, strings.outdated, signIn = false, needsYou = true)

        "clock" -> SyncProblem(strings.clockTitle, strings.clock, signIn = false, needsYou = true)

        "second_factor" -> SyncProblem(
            strings.secondFactorTitle,
            strings.secondFactorDue,
            signIn = true,
            needsYou = true,
        )

        else -> SyncProblem(strings.syncFailedTitle, strings.syncFailed, signIn = false, needsYou = false)
    }

    /** Hears messages to show briefly: why an action didn't work, or that it did. */
    fun onNotice(listener: (String) -> Unit): () -> Unit {
        noticed += listener
        return { noticed -= listener }
    }

    internal fun say(message: String) = noticed.toList().forEach { it(message) }

    internal fun notice(e: Throwable) {
        if (e is ApiError && e.code == ErrorCode.PROFILE_REQUIRED) {
            setupWanted = true
            reload()
        }
        say(problem(e))
    }

    /** What to tell people about [e]. */
    internal fun problem(e: Throwable): String = when (e) {
        is Refused -> strings.error(e.code.value)
        is ApiError -> strings.error(if (e.status == 0) "offline" else e.code?.value)
        is SignInError -> strings.error(e.code)
        else -> strings.error(null).also { println("Rondo: $e") }
    }

    fun syncNow() = act { rondo.syncNow() }

    /** Stops borrowing [id], a deck lent to you. */
    internal suspend fun leave(id: String) {
        rondo.sharing.remove(id, rondo.library.me)
        rondo.syncNow()
    }

    /** Signs this account in for an agent's OAuth request (Hydra's login step); [open] goes on to consent. */
    fun acceptLogin(challenge: String, open: (String) -> Unit) = act {
        open(rondo.agents.acceptLogin(challenge))
    }

    /**
     * Signs out, which empties this device. With changes not yet synced it asks first: [ask] gets
     * the question, and the UI calls again with [confirmed] once the person agrees.
     */
    fun signOut(confirmed: Boolean, ask: (String) -> Unit) = act {
        val pending = rondo.syncer.pending()
        if (pending > 0 && !confirmed) return@act ask(strings.unsynced(pending))
        rondo.reminders.remindHere(false)
        rondo.account.signOut()
        reload()
    }

    /** The current time, as the screens count it: for UIs that show relative times. */
    fun now(): Double = Clock.System.now().toEpochMilliseconds().toDouble()

    fun home() = Home(this)

    fun deck(id: String) = DeckScreen(this, id)

    fun deckSettings(deckId: String) = DeckSettingsScreen(this, deckId)

    fun export(deckId: String) = ExportScreen(this, deckId)

    fun share(deckId: String) = ShareScreen(this, deckId)

    /** Studying a deck and its sub-decks, or every deck (null). */
    fun study(deckId: String?) = StudyScreen(this, rondo.study.deck(deckId))

    /** Studying a smart deck, within its limits. */
    fun studySmart(id: String) = StudyScreen(this, rondo.study.smart(id))

    /** Studying what a filter finds ("Study these"): [address] as [FilterState.address] writes it. */
    fun studyThese(address: String) =
        StudyScreen(this, rondo.study.filter(Filter(parseQueryString(address.removePrefix("?")).toMap())))

    /**
     * Redeems a promo code: the Pro it gives starts when the Pro there is ends. The account is read
     * again, so everything that shows Pro does.
     */
    internal suspend fun redeem(code: String): Subscription {
        val plan = rondo.billing.redeem(code)
        rondo.account.refresh()
        reload()
        return plan
    }

    fun smartDeck(id: String) = SmartDeckScreen(this, id)

    /** Tags to pick from: see [TagsScreen]. */
    fun tags() = TagsScreen(this)

    fun editor(noteId: String?, deckId: String?) = EditorScreen(this, noteId, deckId)

    fun templates() = TemplatesScreen(this)

    /** A note type to edit ([id]), or a new one: from scratch, or a copy of [from]. */
    fun template(id: String?, from: String?) = TemplateScreen(this, id, from)

    /** Browse, opened on [address] as [BrowseState.address] writes it: its filter and sort. */
    fun browse(address: String) = BrowseScreen(this, address)

    fun importer() = ImportScreen(this)

    fun discover() = DiscoverScreen(this)

    /** A deck before it's yours: on Discover or behind a share link ([slug]), or an invitation ([token]). */
    fun preview(slug: String?, token: String?) = PreviewScreen(this, slug, token)

    fun insights() = InsightsScreen(this)

    fun settings() = SettingsScreen(this)

    /** Pro, offered because [feature] needs it: see [ProScreen]. */
    fun pro(feature: String) = ProScreen(this, feature)

    fun signIn() = SignInScreen(this)

    fun consent(challenge: String) = ConsentScreen(this, challenge)

    fun issues() = IssuesScreen(this)

    private companion object {
        const val STAFF_HIDDEN = "staff_hidden"
        const val SETUP_ASKED = "setup_asked"
    }
}
