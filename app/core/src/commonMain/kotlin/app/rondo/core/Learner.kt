package app.rondo.core

import kotlin.js.JsExport
import kotlin.uuid.Uuid

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

/** A learner's own saved filter, studied like a deck, within its own limits. Never lent or published. */
data class SmartDeck(
    val id: String,
    val ownerId: String,
    val name: String,
    /** A fractional index among the owner's smart decks. */
    val position: String,
    val color: Int,
    val filter: Filter,
    val newPerDay: Int,
    val reviewsPerDay: Int,
    val v: Long,
    val icon: String? = null,
    val deletedAt: Long? = null,
) {
    /** A new smart deck, with a new deck's daily limits. */
    constructor(name: String, filter: Filter, position: String, v: Long, ownerId: String) : this(
        id = Uuid.generateV7().toString(), ownerId = ownerId, name = name, position = position, color = 0,
        filter = filter, newPerDay = 20, reviewsPerDay = 200, v = v,
    )

    /** Why [by] may not make this the smart deck that was [stored]: only its owner makes and changes it. */
    fun check(by: Access, stored: SmartDeck?): ErrorCode? = when {
        Uuid.parseOrNull(id) == null -> ErrorCode.INVALID

        stored != null && stored.ownerId != ownerId -> ErrorCode.OWNER_MISMATCH

        ownerId != by.actor -> ErrorCode.FORBIDDEN

        name.isBlank() || name.length > 100 || !Position(position).valid || color !in 0..7 ||
            (icon?.length ?: 0) > 16 || newPerDay !in Deck.NEW_PER_DAY || reviewsPerDay !in Deck.REVIEWS_PER_DAY ||
            !filter.valid -> ErrorCode.INVALID

        else -> null
    }
}

/** Browse's filters as picked: a deck stands for it and its sub-decks, a tag for it and the tags under it. */
data class Filter(
    val text: String? = null,
    val deckIds: List<String>? = null,
    val templateIds: List<String>? = null,
    /** Any of them. */
    val tags: List<String>? = null,
    /** new, learning, review or suspended. */
    val state: String? = null,
    val marked: Boolean? = null,
) {
    /** The filter a link's [params] say: see [params]. What a filter can't hold is left out. */
    constructor(params: Map<String, List<String>>) : this(
        text = params["q"]?.firstOrNull()?.trim()?.ifEmpty { null },
        deckIds = params["deck"]?.filter { Uuid.parseOrNull(it) != null }?.ifEmpty { null },
        templateIds = params["type"]?.filter { Uuid.parseOrNull(it) != null }?.ifEmpty { null },
        tags = params["tag"]?.let(::tagged),
        state = params["state"]?.firstOrNull()?.takeIf { it in STATES },
        marked = true.takeIf { "marked" in params },
    )

    /** The filter as a link's parameters: `q`, `deck`, `type`, `tag`, `state` and `marked`, each when set. */
    val params: Map<String, List<String>> get() = listOfNotNull(
        text?.let { "q" to listOf(it) },
        deckIds?.let { "deck" to it },
        templateIds?.let { "type" to it },
        tags?.let { "tag" to it },
        state?.let { "state" to listOf(it) },
        ("marked" to listOf("1")).takeIf { marked == true },
    ).toMap()

    fun withText(text: String) = copy(text = text.trim().ifEmpty { null })

    /** Notes in [deckId] and its sub-decks; null: every deck. */
    fun withDeck(deckId: String?) = copy(deckIds = listOfNotNull(deckId?.ifBlank { null }).ifEmpty { null })

    fun withTemplate(templateId: String?) =
        copy(templateIds = listOfNotNull(templateId?.ifBlank { null }).ifEmpty { null })

    /** Notes with any of [tags], or a tag under one; none: any notes. */
    fun withTags(tags: List<String>) = copy(tags = tagged(tags))

    /** Cards in [state]: new, learning, review or suspended; anything else: any state. */
    fun withState(state: String?) = copy(state = state?.takeIf { it in STATES })

    fun withMarked(marked: Boolean) = copy(marked = true.takeIf { marked })

    /**
     * What it finds in [tree]: its decks with every live deck under them (none picked: every live
     * deck), so notes in deleted decks never come along.
     */
    fun notes(tree: Tree): NoteFilter {
        val picked = deckIds.orEmpty().takeIf { it.isNotEmpty() } ?: tree.roots.map { it.id }
        val decks = picked.filter { !tree.dead(it) }.flatMap { tree.subtree(it) }.map { it.id }.distinct()
        return NoteFilter(
            text = text.orEmpty(),
            decks = decks.ifEmpty { listOf(NoteFilter.NOWHERE) },
            templates = templateIds.orEmpty(),
            state = state,
            marked = marked == true,
            tags = tags.orEmpty(),
        )
    }

    /** Within the limits a smart deck's filter keeps to. */
    val valid: Boolean get() {
        val ids = deckIds.orEmpty() + templateIds.orEmpty()
        val tags = tags.orEmpty()
        return (text?.length ?: 0) <= 500 && ids.size <= 200 && ids.all { Uuid.parseOrNull(it) != null } &&
            tags.size <= 50 && tags.all { it.length <= Tags.MAX_LENGTH && Tag(it).text == it } &&
            (state == null || state in STATES)
    }

    private companion object {
        val STATES = setOf("new", "learning", "review", "suspended")

        /** [tags] as a filter keeps them: each made a tag, once whatever its case; none: null. */
        fun tagged(tags: List<String>) = tags.mapNotNull { Tag(it).text }.distinctBy { it.lowercase() }.ifEmpty { null }
    }
}

/** The order notes are found in: see [NoteFilter]. */
@JsExport
enum class NoteSort {
    /** Newest first. */
    ADDED,

    /** Oldest first. */
    OLDEST,

    /** The latest changed first. */
    CHANGED,

    /** The soonest due first. */
    DUE,

    /** By their text. */
    ALPHA,
}

/** What Browse and the MCP tools look notes up by. Empty fields match everything. */
data class NoteFilter(
    val text: String = "",
    /** Deck ids to look in (the caller expands sub-decks); empty: every deck. */
    val decks: List<String> = emptyList(),
    val templates: List<String> = emptyList(),
    /** new, learning, review or suspended; needs per-card state, so devices only. */
    val state: String? = null,
    /** Only marked notes (any flag colour counts). */
    val marked: Boolean = false,
    /** Notes with any of these tags, or a tag under one of them. */
    val tags: List<String> = emptyList(),
    /** Only notes added after this one: the next page, for results in the order notes were added. */
    val after: String? = null,
) {
    companion object {
        /** No deck has this id: decks that are all gone find nothing, where none at all would find everything. */
        const val NOWHERE = "00000000-0000-0000-0000-000000000000"
    }
}
