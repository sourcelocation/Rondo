package app.rondo.core.study.infrastructure

import app.rondo.core.kernel.infrastructure.Record
import app.rondo.core.study.domain.Settings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A row of `settings`: the learner's own, one row. */
@Serializable
@SerialName("settings")
class SettingsRecord(
    @SerialName("user_id") val userId: String,
    val grading: Int,
    val theme: Int,
    @SerialName("text_size") val textSize: Int,
    val timezone: String? = null,
    @SerialName("fsrs_weights") val fsrsWeights: String? = null,
    @SerialName("reminder_at") val reminderAt: Int? = null,
    override val v: Long,
) : Record() {
    override val id: String get() = userId

    constructor(s: Settings) : this(
        s.userId,
        s.grading,
        s.theme,
        s.textSize,
        s.timezone,
        s.fsrsWeights,
        s.reminderAt,
        s.v,
    )

    fun settings() = Settings(userId, grading, theme, textSize, v, timezone, fsrsWeights, reminderAt)
}
