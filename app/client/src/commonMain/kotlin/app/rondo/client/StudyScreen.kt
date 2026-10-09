package app.rondo.client

import app.rondo.core.CardSide
import app.rondo.core.Tree
import app.rondo.core.nowMillis
import kotlin.js.JsExport

/** An answer button: its rating (1 Again … 4 Easy), name, and when the card would come back. */
@JsExport
class Answer internal constructor(val rating: Int, val label: String, val interval: String)

@JsExport
class StudyState internal constructor(
    val front: CardSide?,
    /** Set once the answer shows. */
    val back: CardSide?,
    val answers: Array<Answer>,
    val done: Int,
    val left: Int,
    val finished: Boolean,
    val canUndo: Boolean,
    /** Whether a typed answer matched, after showing the answer of a type-in card. */
    val correct: Boolean?,
    val typed: String?,
    val opened: Array<String>,
    val noteId: String?,
    val deck: String?,
    /** Marked to look at later (any flag colour counts). */
    val marked: Boolean,
    val canEdit: Boolean,
    /** The deck's language (BCP 47): pieces with speech are in it. Null: nothing is read aloud. */
    val language: String?,
    /** Once it's finished: reviews not due yet that could come early, weakest first. */
    val ahead: Int,
)

/**
 * Studying what [scopes] offer (a deck, every deck, a smart deck, or Browse's "Study these"):
 * reveal, answer, undo, and the card's own actions.
 */
@JsExport
class StudyScreen internal constructor(override val app: App, scopes: suspend (Tree) -> List<Scope>) :
    Screen<StudyState>() {
    override val live get() = false
    private val session = Session(app.rondo.store, app.rondo.library, scopes)
    private var started = false
    private var revealed = false
    private var typed: String? = null
    private var shownAt = 0L
    private var marked: Boolean? = null

    override suspend fun load(): StudyState {
        if (!started) {
            session.start()
            started = true
            shownAt = nowMillis()
        }
        val card = session.current
        val grading = app.rondo.store.settings().grading
        val intervals = if (revealed) session.intervals() else emptyList()
        val ratings = if (grading == 2) listOf(1, 3) else listOf(1, 2, 3, 4)
        val names = mapOf(1 to strings.again, 2 to strings.hard, 3 to strings.good, 4 to strings.easy)
        val expected = card?.side(false)?.pieces?.firstOrNull { it.kind == "typein" }?.text
        return StudyState(
            card?.side(false), if (revealed) card?.side(true) else null,
            ratings.mapNotNull { r ->
                intervals.getOrNull(r - 1)?.let { Answer(r, names.getValue(r), strings.interval(it.toDouble())) }
            }.toTypedArray(),
            session.done, session.left, card == null, session.canUndo,
            // Typing is optional: an empty box just shows the answer.
            typed?.takeIf { revealed && expected != null && it.isNotBlank() }
                ?.let { Session.typedCorrectly(it, expected!!) },
            typed?.ifBlank { null },
            session.opened.mapNotNull { app.rondo.store.tree()[it]?.name }.toTypedArray(),
            card?.note?.id, card?.deck?.name, marked ?: ((card?.state?.flag ?: 0) > 0),
            card != null && app.rondo.library.access().canWrite(card.deck.id) == null,
            card?.let { app.rondo.store.tree().language(it.deck.id) },
            if (card == null) session.aheadLeft() else 0,
        )
    }

    /** Shows the answer; [typed] is what was typed into a type-in box. */
    fun reveal(typed: String?) = act {
        revealed = true
        this.typed = typed
        show(load())
    }

    fun answer(rating: Int) = act {
        if (!revealed) return@act
        session.answer(rating, (nowMillis() - shownAt).coerceIn(0, 60_000).toInt())
        next()
    }

    fun undo() = act {
        session.undo()
        next()
    }

    /** Goes on with reviews not due yet, the most at risk first. */
    fun goAhead() = act {
        session.goAhead()
        next()
    }

    private suspend fun next() {
        revealed = false
        typed = null
        marked = null
        shownAt = nowMillis()
        show(load())
        app.rondo.syncSoon()
    }

    private fun card(block: suspend (noteId: String, card: Int) -> Unit) = act {
        val c = session.current ?: return@act
        block(c.note.id, c.card)
        session.start()
        next()
    }

    fun suspendNote() = card { note, _ -> app.rondo.library.suspendCards(note, true) }

    fun bury() = card { note, card -> app.rondo.library.bury(note, card) }

    fun mark(on: Boolean) = act {
        val c = session.current ?: return@act
        app.rondo.library.flag(c.note.id, c.card, if (on) 1 else 0)
        marked = on
        show(load())
    }

    /** Shows the card again after its note was edited in place; the answer stays shown if it was. */
    fun refresh() = act {
        session.reload()
        show(load())
    }
}
