package app.rondo.client

import app.rondo.core.Markup
import app.rondo.core.Tree
import app.rondo.core.model.Deck
import app.rondo.core.model.Note
import kotlin.js.JsExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * One screen's state for any UI. [state] is null until it first loads; [watch] hears every change.
 * Actions are plain methods: they run in the background and report failures as app notices. They
 * finish even when the screen closes first (a UI may leave right after saving); a closed screen just
 * stops showing. A [live] screen reloads whenever the database changes.
 */
@JsExport
abstract class Screen<S : Any> internal constructor() {
    internal abstract val app: App
    internal open val live: Boolean get() = true
    var state: S? = null
        private set
    private val watchers = ArrayList<() -> Unit>()
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loading: Job? = null
    private var started = false
    private var closed = false

    internal abstract suspend fun load(): S

    /** Calls [watcher] after every change of [state]; the returned function stops it. */
    fun watch(watcher: () -> Unit): () -> Unit {
        watchers += watcher
        if (!started) {
            started = true
            if (live) scope.launch { app.rondo.store.changes.collect { reload() } } else reload()
        }
        return { watchers -= watcher }
    }

    fun close() {
        closed = true
        scope.cancel()
    }

    internal fun reload() {
        loading?.cancel()
        loading = scope.launch { show(load()) }
    }

    internal fun show(s: S) {
        if (closed) return
        state = s
        watchers.toList().forEach { it() }
    }

    internal fun act(block: suspend () -> Unit) {
        app.rondo.scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                app.notice(e)
            }
        }
    }
}

@JsExport
class Choice internal constructor(val id: String, val label: String)

/** [decks] as pickers list them: by path, alphabetically. */
internal fun Tree.choices(decks: Collection<Deck>): List<Choice> =
    decks.map { Choice(it.id, path(it.id).joinToString(" › ")) }.sortedBy { it.label.lowercase() }

/** A note as lists name it: its first field, as plain text. */
internal fun label(n: Note): String {
    val first = n.fields.entries.minByOrNull { it.key.toIntOrNull() ?: 0 }?.value.orEmpty()
    return Markup.plain(first).take(80).ifBlank { "…" }
}
