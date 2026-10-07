package app.rondo.client

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.rondo.core.Days
import app.rondo.core.Fsrs
import app.rondo.core.Optimizer
import app.rondo.core.nowMillis
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
 * Rondo on one device: storage, the account, sync, the library and studying. Syncs every five
 * minutes while signed in, a few seconds after local edits, and on [syncSoon].
 */
class Rondo private constructor(val platform: Platform, val store: Store, val scope: CoroutineScope) {
    val net: Net = Net(platform) { account.token }
    val account: Account = Account(platform, store, net)
    val media = MediaFiles(store, net)
    val syncer = Syncer(store, net, media)
    val library = Library(store)
    val anki = AnkiImport(platform, store, media, library)
    val reminders = Reminders(platform, store, net, library)
    private val status = MutableStateFlow(SyncStatus())
    val sync: StateFlow<SyncStatus> get() = status
    private var pending: Job? = null

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
        if (nowMillis() - (store.meta("optimized")?.toLongOrNull() ?: 0) < 7 * Days.DAY) return
        store.putMeta("optimized", nowMillis().toString())
        val settings = store.settings()
        val days = Days(settings.timezone)
        // Each card's reviews as the optimizer takes them: (days since the review before, rating) pairs.
        val cards = store.q.allReviews().awaitAsList().groupBy { it.subject_id to it.card }.values.map { reviews ->
            IntArray(reviews.size * 2) { i ->
                val r = reviews[i / 2]
                when {
                    i % 2 == 1 -> r.value_?.toInt() ?: 3
                    i == 0 -> 0
                    else -> (days.index(r.at) - days.index(reviews[i / 2 - 1].at)).toInt()
                }
            }
        }
        val start = Fsrs.parse(settings.fsrsWeights) ?: Fsrs.DEFAULT
        val weights = Optimizer.fit(cards, start, pause = { yield() }) ?: return
        store.save(store.settings().copy(fsrsWeights = weights.joinToString(","), v = store.clock()))
        store.refoldAll()
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
                e.code?.value == "second_factor_required" -> "second_factor"
                e.status == 401 -> "signed_out".also { account.refresh() }
                e.code?.value == "clock_ahead" -> "clock"
                else -> "failed"
            }
        }
        status.emit(SyncStatus(false, code, if (code == null) nowMillis() else status.value.at))
    }
}
