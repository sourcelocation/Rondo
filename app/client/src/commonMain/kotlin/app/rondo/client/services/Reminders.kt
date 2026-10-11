package app.rondo.client.services

import app.rondo.client.LocalReminder
import app.rondo.client.Platform
import app.rondo.client.net.Net
import app.rondo.client.storage.Store
import app.rondo.client.strings
import app.rondo.core.Days
import app.rondo.core.DeckLoad
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Reminders, once a day at the person's time when cards are due and they haven't studied. After
 * each sync the device sends a summary (cards due on each of the next 7 days if nothing is
 * studied, and cards learned), which the server reminds by and shows on profiles. A device that
 * schedules reminders itself covers the next 7 days and tells the server, which leaves it alone
 * until they run out.
 */
class Reminders(
    private val platform: Platform,
    private val store: Store,
    private val net: Net,
    private val library: Library,
) {
    private fun now() = Clock.System.now().toEpochMilliseconds()

    /** Whether this device reminds: by pushes from the server, or by its own schedule. */
    val here: Boolean get() = platform.read(DEVICE) != null || platform.read(LOCAL) != null

    /** Cards due on each of the next 7 days if nothing is studied, today first, as deck limits allow. */
    suspend fun forecast(now: Long = now()): List<Int> {
        val days = Days(store.settings.get().timezone)
        val today = days.index(now)
        val tree = store.decks.tree()
        val loads = library.loads(now)
        val levels = tree.levels({ loads[it] ?: DeckLoad() }, store.events.unlocked())
        fun total(load: (String) -> DeckLoad) = tree.roots.sumOf { tree.counts(it.id, load, levels).total }
        return List(7) { k ->
            if (k == 0) return@List total { loads[it] ?: DeckLoad() }
            // A later day, if nothing is studied until then: everything learning, reviews due by its end.
            val due = store.cards.dueBy(days.start(today + k + 1))
            total { id ->
                val (review, learning) = due[id] ?: (0 to 0)
                (loads[id] ?: DeckLoad()).copy(
                    learningDue = learning,
                    reviewDue = review,
                    newToday = 0,
                    reviewsToday = 0,
                )
            }
        }
    }

    /** After a sync: sends the summary when it changed, and reschedules this device's own reminders. */
    suspend fun update(now: Long = now()) {
        val today = Days(store.settings.get().timezone).index(now)
        val due = forecast(now)
        val learned = store.cards.totals().learned
        // The server counts days from the one it got the summary on, so a new day sends it again.
        val summary = "$today:${due.joinToString(",")}:$learned"
        if (store.meta(SUMMARY) != summary) {
            net.put("/api/me/summary", Summary(due, learned))
            store.putMeta(SUMMARY, summary)
        }
        schedule(due, now)
    }

    /**
     * Schedules this device's reminders for the next 7 days, skipping today's once studied, and
     * tells the server they're covered until then.
     */
    suspend fun schedule(due: List<Int>? = null, now: Long = now()) {
        val local = platform.notifications ?: return
        val settings = store.settings.get()
        val at = settings.reminderAt
        if (at == null || platform.read(LOCAL) == null || !local.allowed(ask = false)) {
            return local.schedule(emptyList())
        }
        val days = Days(settings.timezone)
        val today = days.index(now)
        val counts = due ?: forecast(now)
        val studied = store.events.reviewsSince(days.start(today)).isNotEmpty()
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
            platform.read(DEVICE)?.let { id -> runCatching { net.delete("/api/me/push-devices/$id") } }
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
        return runCatching { net.get("/api/push/key").read<PushKey>().key }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Registers this device's push subscription with the server, for reminders. */
    private suspend fun register(ask: Boolean, localUntil: Long?): Boolean {
        val key = pushKey() ?: return false
        val target = platform.push?.subscribe(key, ask) ?: return false
        val until = localUntil?.let { Instant.fromEpochMilliseconds(it).toString() }
        val device = PushDevice(target.platform, target.endpoint, target.p256dh, target.auth, reminders = true, until)
        platform.write(DEVICE, net.put("/api/me/push-devices", device).read<PushDeviceId>().id)
        return true
    }

    /** Cards due on each of the next 7 days if nothing is studied, and cards learned. */
    @Serializable
    private class Summary(val due: List<Int>, val learned: Int)

    @Serializable
    private class PushDevice(
        val platform: String,
        val endpoint: String,
        val p256dh: String?,
        val auth: String?,
        val reminders: Boolean,
        @SerialName("local_until") val localUntil: String?,
    )

    @Serializable
    private class PushDeviceId(val id: String)

    @Serializable
    private class PushKey(val key: String)

    private companion object {
        const val DEVICE = "push.device"
        const val LOCAL = "reminders.local"
        const val SUMMARY = "summary"
        const val COVERED = "reminders.covered"
    }
}
