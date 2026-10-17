package app.rondo.core.study.domain

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** Days as the learner lives them: in their time zone, starting at 4 am. */
class Days(timezone: String?, private val startHour: Int = 4) {
    private val zone = timezone?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: TimeZone.currentSystemDefault()

    fun index(at: Long): Long =
        Instant.fromEpochMilliseconds(at - startHour * HOUR).toLocalDateTime(zone).date.toEpochDays()

    fun start(day: Long): Long =
        LocalDateTime(LocalDate.fromEpochDays(day), LocalTime(startHour, 0)).toInstant(zone).toEpochMilliseconds()

    /** The moment [minutes] after midnight on the calendar [date] (in epoch days), in this time zone. */
    fun at(date: Long, minutes: Int): Long {
        val time = LocalTime(minutes / 60, minutes % 60)
        return LocalDateTime(LocalDate.fromEpochDays(date), time).toInstant(zone).toEpochMilliseconds()
    }

    companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
