package app.rondo.client

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.rondo.core.model.ErrorCode
import app.rondo.core.model.ReportRequest
import app.rondo.core.nowMillis
import kotlin.js.JsExport
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
) {
    internal fun request(): ReportRequest {
        val target = ReportRequest.TargetKind.entries.first { it.value == kind }
        return ReportRequest(target, id, reason, law, note?.ifBlank { null }, goodFaith)
    }
}

/**
 * A notification the server wrote, to show once: as a dialog that waits for the person ([modal]),
 * or briefly. [link] is a path on Rondo's origin (`/docs/…`, `/app/…`).
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
    val sync: String?,
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
        val s = rondo.store.settings()
        val sync = rondo.sync.value
        val me = rondo.account.me
        val issues = rondo.store.q.rejects().awaitAsList().size
        val roles = me?.roles.orEmpty()
        val permissions = me?.permissions.orEmpty()
        val next = me?.notifications.orEmpty().firstOrNull { it.id !in shown }?.let {
            NotificationItem(it.id, it.kind, it.presentation == "modal", it.title, it.body, it.linkLabel, it.linkUrl)
        }
        val unconfirmed = me != null && me.profileConfirmed == false
        val setup = unconfirmed && (setupWanted || rondo.platform.read(SETUP_ASKED) == null)
        return AppState(
            rondo.account.signedIn, me?.name, rondo.account.email, me?.pro == true, sync.busy,
            sync.code?.let(::syncMessage), sync.at?.toDouble(), issues, s.grading, s.theme, s.textSize,
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
        act {
            online { rondo.net.api.markSeen(id).ok() }
            rondo.account.forget(id)
        }
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
        online { rondo.net.api.report(report.request()).ok() }
        say(strings.reportSent)
        done()
    }

    private fun syncMessage(code: String) = when (code) {
        "offline" -> strings.offline
        "signed_out" -> strings.signedOutSync
        "outdated" -> strings.outdated
        "clock" -> strings.clock
        "second_factor" -> strings.secondFactorDue
        else -> strings.syncFailed
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

    /** Signs this account in for an agent's OAuth request (Hydra's login step); [open] goes on to consent. */
    fun acceptLogin(challenge: String, open: (String) -> Unit) = act {
        open(online { rondo.net.api.acceptLogin(challenge).ok() }.url)
    }

    /**
     * Signs out, which empties this device. With changes not yet synced it asks first: [ask] gets
     * the question, and the UI calls again with [confirmed] once the person agrees.
     */
    fun signOut(confirmed: Boolean, ask: (String) -> Unit) = act {
        val pending = rondo.store.pending()
        if (pending > 0 && !confirmed) return@act ask(strings.unsynced(pending))
        rondo.reminders.remindHere(false)
        rondo.account.signOut()
        reload()
    }

    /** The current time, as the screens count it: for UIs that show relative times. */
    fun now(): Double = nowMillis().toDouble()

    fun home() = Home(this)

    fun deck(id: String) = DeckScreen(this, id)

    fun share(deckId: String) = ShareScreen(this, deckId)

    /** Studying a deck and its sub-decks, or every deck (null). */
    fun study(deckId: String?) = StudyScreen(this) { DeckScope.of(rondo.store, deckId, it) }

    /** Studying a smart deck, within its limits. */
    fun studySmart(id: String) = StudyScreen(this) {
        listOfNotNull(
            rondo.store.smartDeck(id)?.takeIf {
                it.deletedAt == null
            }?.let { FilterScope.of(rondo.store, it) },
        )
    }

    /** Studying what Browse finds with these filters ("Study these"): see [BrowseScreen.filter]. */
    fun studyThese(
        text: String,
        deckId: String?,
        templateId: String?,
        tags: Array<String>,
        cardState: String?,
        marked: Boolean,
    ) = StudyScreen(this) {
        listOf(FilterScope.unlimited(rondo.store, picked(text, deckId, templateId, tags, cardState, marked)))
    }

    fun smartDeck(id: String) = SmartDeckScreen(this, id)

    /** Tags to pick from: see [TagsScreen]. */
    fun tags() = TagsScreen(this)

    fun editor(noteId: String?, deckId: String?) = EditorScreen(this, noteId, deckId)

    fun templates() = TemplatesScreen(this)

    /** A note type to edit ([id]), or a new one: from scratch, or a copy of [from]. */
    fun template(id: String?, from: String?) = TemplateScreen(this, id, from)

    /** Browse, in [deckId]; with [smartId], to change that smart deck's rules. */
    fun browse(deckId: String?, smartId: String?) = BrowseScreen(this, deckId, smartId)

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
