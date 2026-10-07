package app.rondo.client

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.rondo.core.Days
import app.rondo.core.Memory
import app.rondo.core.NoteFilter
import app.rondo.core.Templates
import app.rondo.core.nowMillis
import kotlin.js.JsExport
import kotlin.math.roundToInt

/** A note in Browse. [state]: new, learning, review or suspended (its least advanced card). */
@JsExport
class NoteItem internal constructor(
    val id: String,
    val text: String,
    val deck: String,
    val template: String,
    val state: String,
    val due: Double?,
    val marked: Boolean,
    val canEdit: Boolean,
)

@JsExport
class BrowseState internal constructor(
    val notes: Array<NoteItem>,
    val more: Boolean,
    val text: String,
    val deckId: String?,
    val templateId: String?,
    val cardState: String?,
    val marked: Boolean,
    /** added, changed, due or alpha. */
    val sort: String,
    val decks: Array<Choice>,
    val templates: Array<Choice>,
)

/** Finding notes with filter pickers, and changing many at once. */
@JsExport
class BrowseScreen internal constructor(override val app: App, private var deckId: String?) : Screen<BrowseState>() {
    private val store get() = app.rondo.store
    private val library get() = app.rondo.library
    private var text = ""
    private var templateId: String? = null
    private var cardState: String? = null
    private var marked = false
    private var sort = "added"
    private var limit = 200

    override suspend fun load(): BrowseState {
        val tree = store.tree()
        val access = library.access()
        val templates = store.templates()
        val filter = NoteFilter(
            text = text,
            decks = deckId?.let { id -> tree.subtree(id).map { it.id } }.orEmpty(),
            templates = listOfNotNull(templateId),
            state = cardState,
            marked = marked,
        )
        val found = store.search(filter, limit + 1, sort)
        val notes = found.take(limit)
        val cards = notes.map { it.id }.chunked(500).flatMap { store.q.cardsOfNotes(it).awaitAsList() }
            .groupBy { it.note_id }
        val items = notes.map { n ->
            val c = cards[n.id].orEmpty()
            val live = c.filter { !it.suspended }
            val state = when {
                live.isEmpty() -> "suspended"
                live.any { it.state == Memory.NEW.toLong() } -> "new"
                live.any { it.state != Memory.REVIEW.toLong() } -> "learning"
                else -> "review"
            }
            val template = templates[n.templateId]
            NoteItem(
                id = n.id,
                text = label(n),
                deck = tree.path(n.deckId).joinToString(" › "),
                template = template?.name.orEmpty(),
                state = state,
                due = live.mapNotNull { it.due }.minOrNull()?.toDouble(),
                marked = c.any { it.flag > 0 },
                canEdit = access.canWrite(n.deckId) == null && template?.kind != Templates.ANKI,
            )
        }
        val decks = tree.choices(tree.byId.values.filter { !tree.dead(it.id) })
        val types = templates.values.filter { it.deletedAt == null }.map { Choice(it.id, it.name) }
            .sortedBy { it.label.lowercase() }
        return BrowseState(
            items.toTypedArray(), found.size > limit, text, deckId, templateId, cardState, marked, sort,
            decks.toTypedArray(), types.toTypedArray(),
        )
    }

    /**
     * Sets every filter at once; null or empty means any. [cardState]: new, learning, review or
     * suspended; [sort]: added, changed, due or alpha.
     */
    fun filter(text: String, deckId: String?, templateId: String?, cardState: String?, marked: Boolean, sort: String) =
        act {
            this.text = text
            this.deckId = deckId?.ifBlank { null }
            this.templateId = templateId?.ifBlank { null }
            this.cardState = cardState?.ifBlank { null }
            this.marked = marked
            this.sort = sort.takeIf { it in SORTS } ?: "added"
            limit = 200
            reload()
        }

    fun more() = act {
        limit += 200
        reload()
    }

    private fun each(ids: Array<String>, block: suspend (String) -> Unit) = act {
        ids.forEach { block(it) }
        app.rondo.syncSoon()
    }

    fun move(ids: Array<String>, deckId: String) = act {
        library.moveNotes(ids.toList(), deckId)
        app.rondo.syncSoon()
    }

    fun delete(ids: Array<String>) = act {
        library.deleteNotes(ids.toList())
        app.rondo.syncSoon()
    }

    fun suspend(ids: Array<String>, on: Boolean) = each(ids) { library.suspendCards(it, on) }

    fun reset(ids: Array<String>) = each(ids) { library.reset(it) }

    fun mark(ids: Array<String>, on: Boolean) = each(ids) { id ->
        store.q.cardsOf(id).awaitAsList().forEach { library.flag(id, it.card.toInt(), if (on) 1 else 0) }
    }

    private companion object {
        val SORTS = setOf("added", "changed", "due", "alpha")
    }
}

@JsExport
class ImportState internal constructor(
    val working: Boolean,
    val deckId: String?,
    val message: String?,
    val skipped: String?,
    val failed: Boolean,
    val decks: Array<Choice>,
)

/** Importing an Anki package. */
@JsExport
class ImportScreen internal constructor(override val app: App) : Screen<ImportState>() {
    override val live get() = false
    private var result = ImportState(false, null, null, null, false, emptyArray())

    override suspend fun load(): ImportState {
        val tree = app.rondo.store.tree()
        val mine = tree.byId.values.filter { it.ownerId == app.rondo.store.me && !tree.dead(it.id) }
        val decks = tree.choices(mine).toTypedArray()
        return ImportState(result.working, result.deckId, result.message, result.skipped, result.failed, decks)
    }

    /** Imports [bytes] (a .apkg or .colpkg) under [parentId], or at the top level. */
    fun run(bytes: ByteArray, translate: Boolean, history: Boolean, parentId: String?) = act {
        result = ImportState(true, null, null, null, false, emptyArray())
        show(load())
        result = try {
            val r = app.rondo.anki.run(bytes, translate, history, parentId)
            app.rondo.syncSoon()
            val skipped = r.skipped.takeIf { it > 0 }?.let(strings::skipped)
            ImportState(false, r.deck, strings.imported(r.notes, r.media), skipped, false, emptyArray())
        } catch (e: Refused) {
            ImportState(false, null, strings.importFailed, null, true, emptyArray())
        }
        show(load())
    }
}

@JsExport
class InsightsState internal constructor(
    /** Reviews a day for the last 365 days, oldest first; the last is today. */
    val days: Array<Int>,
    val streak: Int,
    /** Learned cards answered correctly in the last 30 days, percent; null without enough reviews. */
    val retention: Int?,
    val cards: Int,
    val learned: Int,
    val suspended: Int,
    /** Reviews due on each of the next 30 days, today first. */
    val forecast: Array<Int>,
    val minutes: Int,
)

/** A year of answers a day (oldest first, today last), the streak they make, and tomorrow's reviews. */
internal class Activity(val days: IntArray, val streak: Int, val tomorrow: Int)

internal suspend fun Store.activity(now: Long = nowMillis()): Activity {
    val days = Days(settings().timezone)
    val today = days.index(now)
    val perDay = IntArray(365)
    for (r in q.reviewsSince(days.start(today - 364)).awaitAsList()) {
        (days.index(r.at) - (today - 364)).toInt().takeIf { it in 0..364 }?.let { perDay[it]++ }
    }
    val streak = perDay.reversed().let { d -> (if (d[0] == 0) d.drop(1) else d).takeWhile { it > 0 }.size }
    val tomorrow = q.forecast(days.start(today + 1), days.start(today + 2) - 1).awaitAsList().size
    return Activity(perDay, streak, tomorrow)
}

/** How studying goes: a year of reviews, true retention, counts and what's coming. */
@JsExport
class InsightsScreen internal constructor(override val app: App) : Screen<InsightsState>() {
    override suspend fun load(): InsightsState {
        val store = app.rondo.store
        val now = nowMillis()
        val days = Days(store.settings().timezone)
        val today = days.index(now)
        val reviews = store.q.reviewsSince(days.start(today - 364)).awaitAsList()
        val activity = store.activity(now)

        // True retention: answers to cards last seen at least a day before, over the last 30 days.
        val monthStart = now - 30 * Days.DAY
        var passed = 0
        var total = 0
        var ms = 0L
        for ((_, list) in reviews.groupBy { it.subject_id to it.card }) {
            list.zipWithNext().forEach { (a, b) ->
                if (b.at >= monthStart && b.at - a.at >= 20 * Days.HOUR) {
                    total++
                    if ((b.value_ ?: 0) > 1) passed++
                }
            }
            ms += list.filter { it.at >= monthStart }.sumOf { it.duration_ms ?: 0 }
        }
        val forecast = IntArray(30)
        for (row in store.q.forecast(0, days.start(today + 30)).awaitAsList()) {
            val day = (days.index(row.due ?: continue) - today).toInt().coerceAtLeast(0)
            if (day < 30) forecast[day]++
        }
        val totals = store.q.cardTotals().awaitAsOne()
        return InsightsState(
            activity.days.toTypedArray(),
            activity.streak,
            if (total >= 10) (100.0 * passed / total).roundToInt() else null,
            totals.cards.toInt(),
            totals.learned.toInt(),
            totals.suspended.toInt(),
            forecast.toTypedArray(),
            (ms / 60_000).toInt(),
        )
    }
}
