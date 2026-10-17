package app.rondo.core.study.domain

/** One card's memory for one learner. [due] and [last] are epoch milliseconds. */
data class Memory(
    val state: Int = NEW,
    val step: Int = 0,
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val due: Long? = null,
    val last: Long? = null,
    val reps: Int = 0,
    val lapses: Int = 0,
) {
    val started: Boolean get() = state != NEW && stability > 0

    companion object {
        const val NEW = 0
        const val LEARNING = 1
        const val REVIEW = 2
        const val RELEARNING = 3
    }
}
