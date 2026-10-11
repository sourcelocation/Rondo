package app.rondo.client

import app.rondo.client.net.ApiError
import app.rondo.client.net.Net
import app.rondo.client.services.Account
import app.rondo.client.services.Agents
import app.rondo.client.services.Billing
import app.rondo.client.services.DeviceWorkspace
import app.rondo.client.services.Discover
import app.rondo.client.services.Export
import app.rondo.client.services.Import
import app.rondo.client.services.Library
import app.rondo.client.services.MediaFiles
import app.rondo.client.services.Reminders
import app.rondo.client.services.Sharing
import app.rondo.client.services.Staff
import app.rondo.client.services.Study
import app.rondo.client.services.Syncer
import app.rondo.client.storage.Store
import app.rondo.core.Days
import app.rondo.core.ErrorCode
import app.rondo.core.Fsrs
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Where syncing stands. [code] says why it stopped: offline, signed_out, outdated, clock or failed. */
data class SyncStatus(val busy: Boolean = false, val code: String? = null, val at: Long? = null)

/**
 * Rondo on one device: storage and one of each service. Syncs every five minutes while signed in,
 * a few seconds after local edits, and on [syncSoon].
 */
class Rondo private constructor(val platform: Platform, val store: Store, val scope: CoroutineScope) {
    val net: Net = Net(platform) { account.token }
    val account = Account(platform, store, net)
    val media = MediaFiles(store, net)
    val syncer = Syncer(store, net, media)
    val library = Library(store)
    val study = Study(store, library)
    val imports = Import(platform, library, media)
    val exports = Export(platform, store, library, media)
    val reminders = Reminders(platform, store, net, library)
    val agents = Agents(net)
    val billing = Billing(net)
    val discover = Discover(net)
    val sharing = Sharing(net)
    val staff = Staff(net)

    /** This device's data for an agent (the command-line MCP client). */
    val workspace = DeviceWorkspace(store, library) { syncSoon(500) }

    private val status = MutableStateFlow(SyncStatus())
    val sync: StateFlow<SyncStatus> get() = status
    private var pending: Job? = null

    private fun now() = Clock.System.now().toEpochMilliseconds()

    companion object {
        suspend fun open(
            platform: Platform,
            scope: CoroutineScope = CoroutineScope(SupervisorJob()),
            autoSync: Boolean = true,
        ): Rondo = Rondo(platform, Store.open(platform.driver()), scope).apply {
            account.load()
            if (autoSync) {
                scope.launch {
                    while (true) {
                        syncNow()
                        optimize()
                        delay(5 * 60_000)
                    }
                }
            }
        }
    }

    /** Syncs a moment from now, once, however many edits arrive meanwhile. */
    fun syncSoon(after: Long = 3_000) {
        pending?.cancel()
        pending = scope.launch {
            delay(after)
            syncNow()
        }
    }

    /** Refits FSRS to this learner's reviews about once a week, a step at a time. */
    suspend fun optimize() {
        if (now() - (store.meta("optimized")?.toLongOrNull() ?: 0) < 7 * Days.DAY) return
        store.putMeta("optimized", now().toString())
        val settings = store.settings.get()
        val days = Days(settings.timezone)
        // Each card's reviews as the optimizer takes them: (days since the review before, rating) pairs.
        val cards = store.events.reviews().groupBy { it.subjectId to it.card }.values.map { reviews ->
            IntArray(reviews.size * 2) { i ->
                val r = reviews[i / 2]
                when {
                    i % 2 == 1 -> r.rating
                    i == 0 -> 0
                    else -> (days.index(r.at) - days.index(reviews[i / 2 - 1].at)).toInt()
                }
            }
        }
        val weights = Fsrs(settings.weights).fit(cards, pause = { yield() }) ?: return
        store.settings.save(store.settings.get().copy(fsrsWeights = weights.joinToString(","), v = store.clock()))
        store.cards.refoldAll()
    }

    suspend fun syncNow() {
        if (!account.signedIn) return status.emit(SyncStatus(code = "signed_out"))
        status.emit(status.value.copy(busy = true))
        val code = try {
            // Roles, Pro and notifications come with the account: read it each time.
            account.refresh()
            syncer.sync()
            try {
                reminders.update()
            } catch (_: ApiError) {
                // The next sync sends the summary.
            }
            null
        } catch (e: ApiError) {
            when {
                e.status == 0 -> "offline"
                e.status == 426 -> "outdated"
                e.code == ErrorCode.SECOND_FACTOR_REQUIRED -> "second_factor"
                e.status == 401 -> "signed_out".also { account.refresh() }
                e.code == ErrorCode.CLOCK_AHEAD -> "clock"
                else -> "failed"
            }
        }
        status.emit(SyncStatus(false, code, if (code == null) now() else status.value.at))
    }
}
