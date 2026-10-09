package app.rondo.client

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.rondo.core.Days
import app.rondo.core.nowMillis
import kotlin.js.JsExport
import kotlin.math.roundToInt

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
