package app.rondo.core.study.domain

import app.rondo.core.decks.domain.Access
import app.rondo.core.kernel.domain.ErrorCode

/** A learner's own settings: one row, theirs alone. */
data class Settings(
    val userId: String,
    /** 4 or 2 answer buttons. */
    val grading: Int,
    /** 0 system, 1 light, 2 dark. */
    val theme: Int,
    /** Percent. */
    val textSize: Int,
    val v: Long,
    val timezone: String? = null,
    val fsrsWeights: String? = null,
    /** Minutes after midnight to be reminded at; absent: no reminders. */
    val reminderAt: Int? = null,
) {
    /** The FSRS weights fitted to this learner, when they're saved and within FSRS's bounds. */
    val weights: List<Double>? get() {
        val parsed = fsrsWeights?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() } ?: return null
        return parsed.takeIf { Fsrs(it).weights == it }
    }

    /** Why [by] may not keep these settings, or null. */
    fun check(by: Access): ErrorCode? = when {
        userId != by.actor -> ErrorCode.FORBIDDEN
        grading != 2 && grading != 4 -> ErrorCode.INVALID
        theme !in 0..2 || textSize !in 50..200 || (timezone?.length ?: 0) > 64 -> ErrorCode.INVALID
        fsrsWeights != null && weights == null -> ErrorCode.INVALID
        reminderAt != null && reminderAt !in 0..<24 * 60 -> ErrorCode.INVALID
        else -> null
    }
}
