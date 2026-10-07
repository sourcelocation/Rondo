package app.rondo.client

import app.rondo.core.model.ProfileUpdate
import app.rondo.core.model.PublicProfile
import kotlin.js.JsExport
import kotlin.math.ceil

/**
 * Setting up the profile, all filled in: a username (from the sign-in's name when Rondo picked
 * the current one), the display name and the flag Cloudflare suggests. [usernameNote] says why the
 * username can't be had; [suggestion] is a free one like it. [usernameLocked]: when it can change
 * next (ISO), or null.
 */
@JsExport
class SetupState internal constructor(
    val username: String,
    val usernameNote: String?,
    val suggestion: String?,
    val name: String,
    val flag: String?,
    val usernameLocked: String?,
    val busy: Boolean,
)

/** The profile setup: shown once after signing in, when sharing needs it, and from Profile. */
@JsExport
class ProfileSetupScreen internal constructor(override val app: App) : Screen<SetupState>() {
    override val live get() = false
    private var draft: SetupState? = null

    override suspend fun load(): SetupState = draft ?: start().also { draft = it }

    private suspend fun start(): SetupState {
        val account = app.rondo.account
        val me = account.me
        val suggested = account.suggestedName()
        val current = me?.username.orEmpty()
        val automatic = AUTOMATIC.matches(current)
        val username = if (automatic && suggested != null) usernameFrom(suggested).ifEmpty { current } else current
        val name = me?.name ?: suggested.orEmpty()
        val flag = me?.flag ?: me?.suggestedFlag
        val start = SetupState(username, null, null, name, flag, me?.usernameChangesAt?.toString(), false)
        return if (username != current) checked(start, username) else start
    }

    private suspend fun checked(s: SetupState, username: String): SetupState {
        val result = runCatching { online { app.rondo.net.api.checkUsername(username).ok() } }.getOrNull()
            ?: return s.copy(username = username)
        val note = result.reason?.let(strings::usernameRefusal)
        return s.copy(username = result.username, usernameNote = note, suggestion = result.suggestion)
    }

    private fun SetupState.copy(
        username: String = this.username,
        usernameNote: String? = this.usernameNote,
        suggestion: String? = this.suggestion,
        busy: Boolean = this.busy,
    ) = SetupState(username, usernameNote, suggestion, name, flag, usernameLocked, busy)

    /** Checks a username while it's typed: whether it's free, and one like it when it isn't. */
    fun check(username: String) = act {
        val current = draft ?: return@act
        if (username == current.username && current.usernameNote == null) return@act
        draft = checked(current, username)
        show(draft!!)
    }

    /** Saves everything and confirms the profile; the sheet closes once it's saved. */
    fun save(username: String, name: String, flag: String?) = act {
        val current = draft ?: return@act
        draft = current.copy(busy = true)
        show(draft!!)
        try {
            val me = app.rondo.account.me
            val update = ProfileUpdate(
                username = username.takeIf { it != me?.username },
                name = name.trim(),
                flag = flag.orEmpty(),
                confirm = true,
            )
            app.rondo.account.updateProfile(update)
            app.setupDone()
        } finally {
            draft = draft?.copy(busy = false)
            draft?.let { show(it) }
        }
    }

    /** Not now: the sheet comes back only when something needs the profile. */
    fun skip() = app.skipSetup()

    private companion object {
        val AUTOMATIC = Regex("^user[0-9]+$")

        /** A username out of a name: its letters and digits, lowercased, words joined with _. */
        fun usernameFrom(name: String): String = name.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }
            .joinToString("_")
            .take(20)
            .trim('_')
            .takeIf { it.length >= 3 }
            .orEmpty()
    }
}

/** One day of a year's activity: [level] 0 to 4, for its colour. */
@JsExport
class ActivityDayItem internal constructor(val date: String, val reviews: Int, val level: Int)

/** Someone's reviews: all time, in [year], and in streaks of days. */
@JsExport
class ActivityItem internal constructor(
    val year: Int,
    val years: Array<Int>,
    val reviews: Int,
    val learned: Int?,
    val yearReviews: Int,
    val yearDays: Int,
    val yearMinutes: Int,
    val streak: Int,
    val longestStreak: Int,
    val days: Array<ActivityDayItem>,
)

/**
 * A public profile. [missing]: there's no such person (or they aren't shown). [mine]: it's yours.
 * [movedFrom]: the old username that led here. [joined] is ISO.
 */
@JsExport
class ProfileState internal constructor(
    val missing: Boolean,
    val id: String,
    val username: String,
    val name: String?,
    val flag: String?,
    val joined: String,
    val pro: Boolean,
    val movedFrom: String?,
    val decks: Array<PublicItem>,
    val activity: ActivityItem?,
    val mine: Boolean,
)

/** Someone's public page: who they are, their decks on Discover and their activity. */
@JsExport
class ProfileScreen internal constructor(override val app: App, private val username: String, private var year: Int?) :
    Screen<ProfileState>() {
    override val live get() = false

    override suspend fun load(): ProfileState {
        val response = online { app.rondo.net.api.getProfile(username, year) }
        return if (response.status == 404) missing() else state(response.ok())
    }

    private suspend fun state(p: PublicProfile): ProfileState {
        val tree = app.rondo.store.tree()
        val decks = p.decks.map { it.item(tree[it.deckId] != null) }
        return ProfileState(
            false, p.id, p.username, p.name, p.flag, p.joined.toString(), p.pro, p.movedFrom, decks.toTypedArray(),
            p.activity?.let { a ->
                val most = a.days.maxOfOrNull { it.reviews } ?: 0
                val days = a.days.map { ActivityDayItem(it.date.toString(), it.reviews, level(it.reviews, most)) }
                ActivityItem(
                    a.year, a.years.toTypedArray(), a.reviews, a.learned, a.yearReviews, a.yearDays, a.yearMinutes,
                    a.streak, a.longestStreak, days.toTypedArray(),
                )
            },
            app.rondo.account.me?.id == p.id,
        )
    }

    private fun missing() = ProfileState(true, "", username, null, null, "", false, null, emptyArray(), null, false)

    /** Shows another year's activity. */
    fun showYear(year: Int) = act {
        this.year = year
        show(load())
    }

    private fun level(reviews: Int, most: Int) =
        if (reviews <= 0 || most <= 0) 0 else ceil(4.0 * reviews / most).toInt().coerceIn(1, 4)
}
