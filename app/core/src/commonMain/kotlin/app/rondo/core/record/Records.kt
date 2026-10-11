package app.rondo.core.record

import app.rondo.core.AnkiCardDef
import app.rondo.core.AnkiTemplate
import app.rondo.core.Block
import app.rondo.core.CardDef
import app.rondo.core.Deck
import app.rondo.core.Event
import app.rondo.core.FieldDef
import app.rondo.core.Filter
import app.rondo.core.Memory
import app.rondo.core.Note
import app.rondo.core.RondoTemplate
import app.rondo.core.Settings
import app.rondo.core.SmartDeck
import app.rondo.core.Template
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Rondo's data as it's stored: one record per table row, with the table's columns. Postgres reads
 * and writes them as JSON, Rondo files hold them, and sync carries them, tagged by kind when a list
 * mixes kinds. Each converts to and from its domain object; the domain never sees a column.
 */
@Serializable
sealed class Record {
    abstract val id: String

    /** Its clock, for last-writer-wins; null for what's only ever added (events). */
    abstract val v: Long?

    companion object {
        /** How records are written: every value but absent ones, and unknown ones (from newer apps) skipped. */
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
    }
}

@Serializable
@SerialName("deck")
class DeckRecord(
    override val id: String,
    @SerialName("owner_id") val ownerId: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    val name: String,
    val position: String,
    val icon: String? = null,
    val color: Int,
    val description: String? = null,
    val language: String? = null,
    @SerialName("new_per_day") val newPerDay: Int,
    @SerialName("reviews_per_day") val reviewsPerDay: Int,
    val retention: Int,
    val gated: Boolean,
    @SerialName("unlock_at") val unlockAt: Int,
    override val v: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
) : Record() {
    constructor(d: Deck) : this(
        d.id, d.ownerId, d.parentId, d.name, d.position, d.icon, d.color, d.description, d.language,
        d.newPerDay, d.reviewsPerDay, d.retention, d.gated, d.unlockAt, d.v, d.deletedAt,
    )

    fun deck() = Deck(
        id, name, position, color, newPerDay, reviewsPerDay, retention, gated, unlockAt, v,
        ownerId, parentId, icon, description, language, deletedAt,
    )
}

/** A note type: [kind] 1 is Rondo's, 2 Anki's HTML kept 1:1; [def] its fields and cards. */
@Serializable
@SerialName("template")
class TemplateRecord(
    override val id: String,
    @SerialName("owner_id") val ownerId: String? = null,
    val name: String,
    val kind: Int,
    val def: Def,
    override val v: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
) : Record() {
    @Serializable
    class Def(val fields: List<FieldRecord>, val cards: List<CardRecord>, val css: String? = null)

    @Serializable
    class FieldRecord(val id: Int, val name: String, val kind: String, val tts: Boolean? = null)

    /** A card: Rondo's blocks, or Anki's HTML ([ankiFront], [ankiBack]). */
    @Serializable
    class CardRecord(
        val id: Int,
        val name: String,
        val front: List<BlockRecord> = emptyList(),
        val back: List<BlockRecord> = emptyList(),
        val each: Int? = null,
        val requires: List<List<Int>>? = null,
        @SerialName("anki_front") val ankiFront: String? = null,
        @SerialName("anki_back") val ankiBack: String? = null,
    )

    /** Exactly one of field, divider, label or typein. */
    @Serializable
    class BlockRecord(
        val field: Int? = null,
        val size: String? = null,
        val align: String? = null,
        val divider: Boolean? = null,
        val label: String? = null,
        val typein: Int? = null,
    )

    constructor(t: Template) : this(
        t.id,
        t.ownerId,
        t.name,
        if (t is AnkiTemplate) ANKI else RONDO,
        Def(
            t.fields.map { FieldRecord(it.id, it.name, it.kind.value, it.tts.takeIf { on -> on }) },
            when (t) {
                is RondoTemplate -> t.cards.map { c ->
                    CardRecord(c.id, c.name, c.front.map(::block), c.back.map(::block), c.each, c.requires)
                }

                is AnkiTemplate -> t.cards.map {
                    CardRecord(it.id, it.name, ankiFront = it.frontHtml, ankiBack = it.backHtml)
                }
            },
            (t as? AnkiTemplate)?.css,
        ),
        t.v,
        t.deletedAt,
    )

    /** The note type it stores; null when it isn't one this app knows how to read. */
    fun template(): Template? {
        val fields = def.fields.map { f ->
            val kind = FieldDef.Kind.entries.firstOrNull { it.value == f.kind } ?: return null
            FieldDef(f.id, f.name, kind, f.tts == true)
        }
        return when (kind) {
            RONDO -> RondoTemplate(
                id,
                name,
                fields,
                def.cards.map { c ->
                    CardDef(
                        c.id,
                        c.name,
                        c.front.map {
                            it.block() ?: return null
                        },
                        c.back.map { it.block() ?: return null },
                        c.each,
                        c.requires,
                    )
                },
                v,
                ownerId,
                deletedAt,
            )

            ANKI -> AnkiTemplate(
                id,
                name,
                fields,
                def.cards.map { AnkiCardDef(it.id, it.name, it.ankiFront ?: return null, it.ankiBack ?: return null) },
                def.css.orEmpty(),
                v,
                ownerId,
                deletedAt,
            )

            else -> null
        }
    }

    companion object {
        const val RONDO = 1
        const val ANKI = 2

        private fun block(b: Block) = when (b) {
            is Block.Field -> BlockRecord(field = b.id, size = b.size?.value, align = b.align?.value)
            Block.Divider -> BlockRecord(divider = true)
            is Block.Label -> BlockRecord(label = b.text, size = b.size?.value, align = b.align?.value)
            is Block.TypeIn -> BlockRecord(typein = b.id)
        }

        private fun BlockRecord.block(): Block? {
            val size = size?.let { s -> Block.Size.entries.firstOrNull { it.value == s } }
            val align = align?.let { a -> Block.Align.entries.firstOrNull { it.value == a } }
            return when (listOfNotNull(field, typein, divider?.takeIf { it }, label).size) {
                1 -> when {
                    field != null -> Block.Field(field, size, align)
                    typein != null -> Block.TypeIn(typein)
                    label != null -> Block.Label(label, size, align)
                    else -> Block.Divider
                }

                else -> null
            }
        }
    }
}

/** A note: [fields] by field id; [tags] absent from an app that predates them. */
@Serializable
@SerialName("note")
class NoteRecord(
    override val id: String,
    @SerialName("owner_id") val ownerId: String? = null,
    @SerialName("deck_id") val deckId: String,
    @SerialName("template_id") val templateId: String,
    val fields: Map<String, String>,
    override val v: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
    val tags: String? = null,
) : Record() {
    constructor(n: Note) : this(n.id, n.ownerId, n.deckId, n.templateId, n.fields, n.v, n.deletedAt, n.tags)

    fun note() = Note(id, deckId, templateId, fields, v, ownerId, deletedAt, tags.orEmpty())
}

/** An event: [kind] 1 review, 2 reset, 3 suspend, 4 unsuspend, 5 bury, 6 flag, 7 snapshot, 8 void, 9 unlock. */
@Serializable
@SerialName("event")
class EventRecord(
    override val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("subject_id") val subjectId: String,
    val card: Int,
    val kind: Int,
    val value: Int? = null,
    val at: Long,
    @SerialName("duration_ms") val durationMs: Int? = null,
    val due: Long? = null,
    val stability: Double? = null,
    val difficulty: Double? = null,
    val reps: Int? = null,
    val lapses: Int? = null,
) : Record() {
    override val v: Long? get() = null

    constructor(e: Event) : this(
        e.id, e.userId, e.subjectId, e.card,
        kind = when (e) {
            is Event.Review -> REVIEW
            is Event.Reset -> RESET
            is Event.Suspend -> SUSPEND
            is Event.Unsuspend -> UNSUSPEND
            is Event.Bury -> BURY
            is Event.Flag -> FLAG
            is Event.Snapshot -> SNAPSHOT
            is Event.Void -> VOID
            is Event.Unlock -> UNLOCK
        },
        value = when (e) {
            is Event.Review -> e.rating
            is Event.Flag -> e.color
            is Event.Snapshot -> e.memory.state
            else -> null
        },
        at = e.at,
        durationMs = (e as? Event.Review)?.durationMs,
        due = (e as? Event.Review)?.due ?: (e as? Event.Snapshot)?.memory?.due,
        stability = (e as? Event.Snapshot)?.memory?.stability,
        difficulty = (e as? Event.Snapshot)?.memory?.difficulty,
        reps = (e as? Event.Snapshot)?.memory?.reps,
        lapses = (e as? Event.Snapshot)?.memory?.lapses,
    )

    /** The event it stores; null for a kind this app doesn't know. */
    fun event(): Event? = when (kind) {
        REVIEW -> Event.Review(id, userId, subjectId, card, at, value ?: 3, durationMs, due)

        RESET -> Event.Reset(id, userId, subjectId, card, at)

        SUSPEND -> Event.Suspend(id, userId, subjectId, card, at)

        UNSUSPEND -> Event.Unsuspend(id, userId, subjectId, card, at)

        BURY -> Event.Bury(id, userId, subjectId, card, at)

        FLAG -> Event.Flag(id, userId, subjectId, card, at, value ?: 0)

        SNAPSHOT -> Event.Snapshot(
            id,
            userId,
            subjectId,
            card,
            Memory(
                state = value ?: Memory.NEW,
                stability = stability ?: 0.0,
                difficulty = difficulty ?: 0.0,
                due = due,
                last = at,
                reps = reps ?: 0,
                lapses = lapses ?: 0,
            ),
        )

        VOID -> Event.Void(id, userId, subjectId, at)

        UNLOCK -> Event.Unlock(id, userId, subjectId, at)

        else -> null
    }

    companion object {
        const val REVIEW = 1
        const val RESET = 2
        const val SUSPEND = 3
        const val UNSUSPEND = 4
        const val BURY = 5
        const val FLAG = 6
        const val SNAPSHOT = 7
        const val VOID = 8
        const val UNLOCK = 9
    }
}

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

@Serializable
@SerialName("smart_deck")
class SmartDeckRecord(
    override val id: String,
    @SerialName("owner_id") val ownerId: String,
    val name: String,
    val position: String,
    val icon: String? = null,
    val color: Int,
    val filter: FilterRecord,
    @SerialName("new_per_day") val newPerDay: Int,
    @SerialName("reviews_per_day") val reviewsPerDay: Int,
    override val v: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
) : Record() {
    constructor(s: SmartDeck) : this(
        s.id, s.ownerId, s.name, s.position, s.icon, s.color, FilterRecord(s.filter), s.newPerDay, s.reviewsPerDay,
        s.v, s.deletedAt,
    )

    fun smartDeck() =
        SmartDeck(id, ownerId, name, position, color, filter.filter(), newPerDay, reviewsPerDay, v, icon, deletedAt)
}

/** A smart deck's filter, stored inside it. */
@Serializable
class FilterRecord(
    val text: String? = null,
    @SerialName("deck_ids") val deckIds: List<String>? = null,
    @SerialName("template_ids") val templateIds: List<String>? = null,
    val tags: List<String>? = null,
    val state: String? = null,
    val marked: Boolean? = null,
) {
    constructor(f: Filter) : this(f.text, f.deckIds, f.templateIds, f.tags, f.state, f.marked)

    fun filter() = Filter(text, deckIds, templateIds, tags, state, marked)
}
