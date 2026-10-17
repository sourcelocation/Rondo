package app.rondo.core.notes.domain

import app.rondo.core.decks.domain.Tree
import kotlin.js.JsExport
import kotlin.uuid.Uuid

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
