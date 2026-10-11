package app.rondo.client

import app.rondo.core.CardSide
import app.rondo.core.Deck
import app.rondo.core.FieldDef
import app.rondo.core.Note
import app.rondo.core.RondoTemplate
import app.rondo.core.Template
import app.rondo.core.Tree
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
 * stops showing. A [live] screen reloads whenever the database changes. A screen about one thing can
 * be part of another ([part]), which shows it and closes it with its own.
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
    private val parts = ArrayList<Screen<*>>()

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
        parts.forEach { it.close() }
    }

    /** Holds [screen] as a part of this one, closed with it. */
    internal fun <T : Screen<*>> part(screen: T): T = screen.also { parts += it }

    internal fun reload() {
        loading?.cancel()
        loading = scope.launch { show(load()) }
    }

    internal fun show(s: S) {
        if (closed) return
        state = s
        watchers.toList().forEach { it() }
    }

    /** Every card [note] makes under [t], front and back, as previews show them. */
    internal fun samples(t: Template, note: Note): Array<Sample> = runCatching {
        t.cards(note).map { Sample(t.side(note, it, false), t.side(note, it, true)) }
    }.getOrDefault(emptyList()).toTypedArray()

    /** A note showing each text field's name (a cloze where the field makes the cards): what previews show. */
    internal fun sampleNote(t: Template): Note {
        val each = (t as? RondoTemplate)?.cards?.mapNotNull { it.each }.orEmpty()
        val text = t.fields.filter { it.kind == FieldDef.Kind.TEXT }
        return Note(
            "",
            "",
            t.id,
            text.associate { f ->
                f.id.toString() to
                    if (f.id in each) "{{c1::${f.name}}}" else f.name
            },
            0,
        )
    }

    /** [decks] as pickers list them: by path, alphabetically. */
    internal fun choices(tree: Tree, decks: Collection<Deck>): List<Choice> =
        decks.map { Choice(it.id, tree.path(it.id).joinToString(" › ")) }.sortedBy { it.label.lowercase() }

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

@JsExport
class Sample internal constructor(val front: CardSide, val back: CardSide)
