package app.rondo.client

import app.rondo.core.study.domain.Settings
import kotlin.js.JsExport

/**
 * Reminders: [time] in minutes after midnight, null when off; [here] on this device, which
 * [possible] says it can; [blocked]: the person didn't allow notifications.
 */
@JsExport
class RemindersState internal constructor(
    val time: Int?,
    val here: Boolean,
    val possible: Boolean,
    val blocked: Boolean,
    val busy: Boolean,
)

/** When to be reminded, and on which devices. */
@JsExport
class RemindersScreen internal constructor(override val app: App) : Screen<RemindersState>() {
    override val live get() = false
    private val rondo get() = app.rondo
    private var blocked = false
    private var busy = false

    override suspend fun load(): RemindersState {
        val s = rondo.library.settings()
        val here = rondo.reminders
        return RemindersState(s.reminderAt, here.here, here.possible(), blocked, busy)
    }

    private fun save(block: (Settings) -> Settings) = act {
        rondo.library.settings(block)
        rondo.reminders.schedule()
        rondo.syncSoon()
        show(load())
    }

    /** Reminds at [minutes] after midnight each day; null turns reminders off everywhere. */
    fun setTime(minutes: Int?) = save { it.copy(reminderAt = minutes?.coerceIn(0, 24 * 60 - 1)) }

    /** Reminds on this device too, asking to show notifications if it hasn't. */
    fun setHere(on: Boolean) = act {
        busy = true
        show(load())
        try {
            blocked = !rondo.reminders.remindHere(on)
        } finally {
            busy = false
            show(load())
        }
    }
}
