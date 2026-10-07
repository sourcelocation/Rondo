package app.rondo.client

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.rondo.core.Days
import app.rondo.core.DeckLoad
import app.rondo.core.Learning
import app.rondo.core.model.PushDevice
import app.rondo.core.model.Summary
import app.rondo.core.nowMillis
import kotlin.js.JsExport
import kotlinx.datetime.Instant

/** A reminder the device shows itself at [at] (epoch milliseconds). */
class LocalReminder(val at: Long, val title: String, val body: String)

/** Reminders a device schedules itself, without the server: the phone apps. */
interface LocalNotifications {
    /** Whether reminders may show; [ask] asks the person when they haven't said. */
    suspend fun allowed(ask: Boolean): Boolean

    /** Replaces every scheduled reminder with [reminders]. */
    suspend fun schedule(reminders: List<LocalReminder>)
}

/** Where the server pushes to this device: [platform] is web, apns or fcm; browsers add their keys. */
class PushTarget(val platform: String, val endpoint: String, val p256dh: String? = null, val auth: String? = null)

/** Pushes from the server: Web Push in browsers, APNs and FCM in the apps. */
interface PushChannel {
    /**
     * This device's subscription, made with the server's [key] when there's none yet and [ask]
     * allows asking the person; null when they said no, or weren't asked.
     */
    suspend fun subscribe(key: String, ask: Boolean): PushTarget?

    suspend fun unsubscribe()

    /** Whether pushes can arrive here now (a browser needs its service worker). */
    suspend fun available(): Boolean
}

/**
 * Reminders, once a day at the person's time when cards are due and they haven't studied. After
 * each sync the device sends a [Summary] (cards due on each of the next 7 days if nothing is
 * studied, and cards learned), which the server reminds by and shows on profiles. A device that
 * schedules reminders itself covers the next 7 days and tells the server, which leaves it alone
 * until they run out.
 */
class Reminders internal constructor(
    private val platform: Platform,
    private val store: Store,
    private val net: Net,
    private val library: Library,
) {
    /** Whether this device reminds: by pushes from the server, or by its own schedule. */
    val here: Boolean get() = platform.read(DEVICE) != null || platform.read(LOCAL) != null

    /** Cards due on each of the next 7 days if nothing is studied, today first, as deck limits allow. */
    suspend fun forecast(now: Long = nowMillis()): List<Int> {
        val days = Days(store.settings().timezone)
        val today = days.index(now)
        val tree = store.tree()
        val loads = library.loads(now)
        val levels = Learning.levels(tree, { loads[it] ?: DeckLoad() }, store.q.unlocked().awaitAsList().toSet())
        fun total(load: (String) -> DeckLoad) = tree.roots.sumOf { Learning.counts(tree, it.id, load, levels).total }
        return List(7) { k ->
            if (k == 0) return@List total { loads[it] ?: DeckLoad() }
            // A later day, if nothing is studied until then: everything learning, reviews due by its end.
            val due = store.q.dueBy(days.start(today + k + 1)).awaitAsList().associateBy { it.deck_id }
            total { id ->
                val d = due[id]
                val load = loads[id] ?: DeckLoad()
                load.copy(
                    learningDue = d?.learning_due?.toInt() ?: 0,
                    reviewDue = d?.review_due?.toInt() ?: 0,
                    newToday = 0,
                    reviewsToday = 0,
                )
            }
        }
    }

    /** After a sync: sends the summary when it changed, and reschedules this device's own reminders. */
    suspend fun update(now: Long = nowMillis()) {
        val today = Days(store.settings().timezone).index(now)
        val due = forecast(now)
        val learned = store.q.cardTotals().awaitAsOne().learned.toInt()
        // The server counts days from the one it got the summary on, so a new day sends it again.
        val summary = "$today:${due.joinToString(",")}:$learned"
        if (store.meta(SUMMARY) != summary) {
            online { net.api.putSummary(Summary(due, learned)).ok() }
            store.putMeta(SUMMARY, summary)
        }
        schedule(due, now)
    }

    /**
     * Schedules this device's reminders for the next 7 days, skipping today's once studied, and
     * tells the server they're covered until then.
     */
    suspend fun schedule(due: List<Int>? = null, now: Long = nowMillis()) {
        val local = platform.notifications ?: return
        val settings = store.settings()
        val at = settings.reminderAt
        if (at == null || platform.read(LOCAL) == null || !local.allowed(ask = false)) {
            return local.schedule(emptyList())
        }
        val days = Days(settings.timezone)
        val today = days.index(now)
        val counts = due ?: forecast(now)
        val studied = store.q.reviewsSince(days.start(today)).awaitAsList().isNotEmpty()
        val reminders = (today..today + 7).mapNotNull { date ->
            val moment = days.at(date, at)
            val k = (days.index(moment) - today).toInt()
            val count = counts.getOrNull(k)
            if (count == null || count == 0 || moment <= now || (k == 0 && studied)) return@mapNotNull null
            LocalReminder(moment, strings.reminderTitle(count), strings.reminderBody)
        }
        local.schedule(reminders)
        val until = days.start(today + 7)
        if (platform.read(DEVICE) != null && store.meta(COVERED) != until.toString()) {
            register(ask = false, localUntil = until)
            store.putMeta(COVERED, until.toString())
        }
    }

    /** Turns reminders on this device on or off; false when the person didn't allow notifications. */
    suspend fun remindHere(on: Boolean): Boolean {
        if (!on) {
            platform.read(DEVICE)?.let { id -> runCatching { online { net.api.deletePushDevice(id).ok() } } }
            platform.push?.unsubscribe()
            platform.write(DEVICE, null)
            platform.write(LOCAL, null)
            platform.notifications?.schedule(emptyList())
            return true
        }
        val local = platform.notifications
        if (local != null) {
            if (!local.allowed(ask = true)) return false
            platform.write(LOCAL, "1")
        }
        val pushed = platform.push != null && register(ask = true, localUntil = null)
        if (local == null && !pushed) return false
        store.putMeta(COVERED, "")
        schedule()
        return true
    }

    /** Whether this device can remind at all. */
    suspend fun possible(): Boolean =
        platform.notifications != null || (platform.push?.available() == true && pushKey() != null)

    private suspend fun pushKey(): String? {
        if (platform.push == null) return null
        return runCatching { online { net.api.pushKey().ok() }.key }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Registers this device's push subscription with the server, for reminders. */
    private suspend fun register(ask: Boolean, localUntil: Long?): Boolean {
        val key = pushKey() ?: return false
        val target = platform.push?.subscribe(key, ask) ?: return false
        val which = PushDevice.Platform.entries.first { it.value == target.platform }
        val until = localUntil?.let { Instant.fromEpochMilliseconds(it) }
        val device = PushDevice(which, target.endpoint, reminders = true, target.p256dh, target.auth, until)
        platform.write(DEVICE, online { net.api.putPushDevice(device).ok() }.id)
        return true
    }

    private companion object {
        const val DEVICE = "push.device"
        const val LOCAL = "reminders.local"
        const val SUMMARY = "summary"
        const val COVERED = "reminders.covered"
    }
}

/**
 * Reminders: [time] in minutes after midnight, null when off; [email] too; [here] on this device,
 * which [possible] says it can; [blocked]: the person didn't allow notifications.
 */
@JsExport
class RemindersState internal constructor(
    val time: Int?,
    val email: Boolean,
    val here: Boolean,
    val possible: Boolean,
    val blocked: Boolean,
    val busy: Boolean,
)

/** When to be reminded, and how: on this device, by email, or both. */
@JsExport
class RemindersScreen internal constructor(override val app: App) : Screen<RemindersState>() {
    override val live get() = false
    private val rondo get() = app.rondo
    private var blocked = false
    private var busy = false

    override suspend fun load(): RemindersState {
        val s = rondo.store.settings()
        val here = rondo.reminders
        return RemindersState(s.reminderAt, s.reminderEmail == true, here.here, here.possible(), blocked, busy)
    }

    private fun save(block: (app.rondo.core.model.Settings) -> app.rondo.core.model.Settings) = act {
        val store = rondo.store
        store.save(block(store.settings()).copy(v = store.clock()))
        rondo.reminders.schedule()
        rondo.syncSoon()
        show(load())
    }

    /** Reminds at [minutes] after midnight each day; null turns reminders off everywhere. */
    fun setTime(minutes: Int?) = save { it.copy(reminderAt = minutes?.coerceIn(0, 24 * 60 - 1)) }

    fun setEmail(on: Boolean) = save { it.copy(reminderEmail = on) }

    /** Reminds on this device too, asking to show notifications if it hasn't. */
    fun setHere(on: Boolean) = act {
        busy = true
        show(load())
        try {
            blocked = !rondo.reminders.remindHere(on)
        } finally {
            busy = false
            show(load())
        }
    }
}
