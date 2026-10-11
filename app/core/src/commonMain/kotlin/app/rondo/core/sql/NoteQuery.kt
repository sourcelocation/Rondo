package app.rondo.core.sql

import app.rondo.core.Folded
import app.rondo.core.NoteFilter
import app.rondo.core.Tag

/** A filter as SQL both SQLite and Postgres run: `?` placeholders, ids marked for binding. */
class SqlFilter(val where: String, val args: List<Any>) {
    class Id(val value: String)
}

/** What [filter] asks, as SQL over notes `n` and their cards' states `c`. */
class NoteQuery(private val filter: NoteFilter) {
    /** Over notes (`notes n`); a note matches the state and the mark by any of its cards ([cardStates]: devices). */
    fun notes(cardStates: Boolean): SqlFilter {
        val (parts, args) = ofNotes()
        if (cardStates && filter.state != null) {
            parts += "EXISTS (SELECT 1 FROM card_state c WHERE c.note_id = n.id AND ${state(filter.state)})"
        }
        if (cardStates && filter.marked) {
            parts += "EXISTS (SELECT 1 FROM card_state c WHERE c.note_id = n.id AND c.flag > 0)"
        }
        return SqlFilter(parts.joinToString(" AND "), args)
    }

    /**
     * Over cards, as studying takes them: `card_state c` joined to its note `n`, the state and the
     * mark each card's own. Devices only.
     */
    fun cards(): SqlFilter {
        val (parts, args) = ofNotes()
        return SqlFilter((parts + card).joinToString(" AND "), args)
    }

    /** What it asks of each card itself, its state and mark, over `card_state c`; "1" when nothing. */
    val card: String get() = listOfNotNull(filter.state?.let(::state), "c.flag > 0".takeIf { filter.marked })
        .joinToString(" AND ").ifEmpty { "1" }

    /** What a note itself says: its text, deck, note type and tags. */
    private fun ofNotes(): Pair<MutableList<String>, MutableList<Any>> {
        val f = filter
        val parts = mutableListOf("n.deleted_at IS NULL")
        val args = mutableListOf<Any>()
        for (term in Folded(f.text).text.split(' ').filter { it.isNotBlank() }) {
            parts += "n.search_text LIKE ? ESCAPE '\\'"
            args += "%" + escape(term) + "%"
        }
        if (f.decks.isNotEmpty()) {
            parts += "n.deck_id IN (" + f.decks.joinToString(",") { "?" } + ")"
            args.addAll(f.decks.map { SqlFilter.Id(it) })
        }
        if (f.templates.isNotEmpty()) {
            parts += "n.template_id IN (" + f.templates.joinToString(",") { "?" } + ")"
            args.addAll(f.templates.map { SqlFilter.Id(it) })
        }
        val tags = f.tags.mapNotNull { Tag(it).text }
        if (tags.isNotEmpty()) {
            // Tags are stored space-separated: a tag is " tag " in the padded text, the ones under it " tag::…".
            val padded = "lower(' ' || n.tags || ' ')"
            parts += tags.joinToString(" OR ", "(", ")") {
                "$padded LIKE lower(?) ESCAPE '\\' OR $padded LIKE lower(?) ESCAPE '\\'"
            }
            for (t in tags) args.addAll(listOf("% ${escape(t)} %", "% ${escape(t)}::%"))
        }
        if (f.after != null) {
            // Ids are UUIDv7: in the order notes were added.
            parts += "n.id > ?"
            args += SqlFilter.Id(f.after)
        }
        return parts to args
    }

    private fun state(state: String): String = when (state) {
        "suspended" -> "c.suspended = 1"
        "new" -> "c.suspended = 0 AND c.state = 0"
        "learning" -> "c.suspended = 0 AND c.state IN (1, 3)"
        else -> "c.suspended = 0 AND c.state = 2"
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
