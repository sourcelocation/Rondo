package app.rondo.client

import kotlin.js.JsExport

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

/** How studying goes: a year of reviews, true retention, counts and what's coming. */
@JsExport
class InsightsScreen internal constructor(override val app: App) : Screen<InsightsState>() {
    override suspend fun load(): InsightsState {
        val i = app.rondo.study.insights()
        return InsightsState(
            i.activity.days.toTypedArray(),
            i.activity.streak,
            i.retention,
            i.totals.cards,
            i.totals.learned,
            i.totals.suspended,
            i.forecast.toTypedArray(),
            i.minutes,
        )
    }
}
