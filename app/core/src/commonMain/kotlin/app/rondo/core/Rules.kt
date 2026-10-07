package app.rondo.core

import app.rondo.core.model.Deck
import app.rondo.core.model.ErrorCode
import app.rondo.core.model.Event
import app.rondo.core.model.Note
import app.rondo.core.model.Settings
import app.rondo.core.model.Template

/** Decks by id, with their children in order. */
class Tree(decks: Collection<Deck>) {
    val byId: Map<String, Deck> = decks.associateBy { it.id }
    private val kids: Map<String?, List<Deck>> = decks.filter { it.deletedAt == null }
        .groupBy { it.parentId?.takeIf(byId::containsKey) }
        .mapValues { (_, v) -> v.sortedWith(compareBy({ it.position }, { it.id })) }

    operator fun get(id: String?): Deck? = id?.let(byId::get)

    /** Live top-level decks: no parent, or a parent this tree doesn't have (a lent deck's). */
    val roots: List<Deck> get() = kids[null].orEmpty()

    fun children(id: String): List<Deck> = kids[id].orEmpty()

    fun ancestors(id: String): List<Deck> =
        generateSequence(get(get(id)?.parentId)) { get(it.parentId) }.take(64).toList()

    fun subtree(id: String): List<Deck> = get(id)?.let { root ->
        listOf(root) + children(id).flatMap { subtree(it.id) }
    }.orEmpty()

    /** Whether the deck or one above it was deleted. */
    fun dead(id: String): Boolean =
        (listOfNotNull(get(id)) + ancestors(id)).any { it.deletedAt != null } || get(id) == null

    fun path(id: String): List<String> = (ancestors(id).reversed() + listOfNotNull(get(id))).map { it.name }
}

/** New decks, as every client makes them: 20 new cards and 200 reviews a day, 90% retention, levels off. */
object Decks {
    fun new(name: String, position: String, v: Long, owner: String?, parent: String?): Deck = Deck(
        id = Ids.new(), name = name, position = position, color = 0, newPerDay = 20, reviewsPerDay = 200,
        retention = 90, gated = false, unlockAt = 100, v = v, ownerId = owner, parentId = parent,
    )
}

enum class Role { NONE, READ_ONLY, VIEWER, EDITOR, OWNER }

/**
 * Who may do what, for [actor]: [lends] maps each deck lent to them to its role (1 viewer,
 * 2 editor); an editor writes only while the owner has Pro ([pro]).
 */
class Access(val actor: String, val tree: Tree, val lends: Map<String, Int>, val pro: (String) -> Boolean) {
    fun role(deckId: String?): Role {
        val deck = tree[deckId] ?: return Role.NONE
        val owner = deck.ownerId ?: return Role.READ_ONLY
        if (owner == actor) return Role.OWNER
        for (d in listOf(deck) + tree.ancestors(deck.id)) {
            val lent = lends[d.id] ?: continue
            return if (lent == 2 && pro(owner)) Role.EDITOR else Role.VIEWER
        }
        return Role.NONE
    }

    fun canWrite(deckId: String?): ErrorCode? = when (role(deckId)) {
        Role.OWNER, Role.EDITOR -> null
        Role.READ_ONLY -> ErrorCode.READ_ONLY
        Role.NONE -> if (tree[deckId] == null) ErrorCode.NOT_FOUND else ErrorCode.FORBIDDEN
        Role.VIEWER -> ErrorCode.FORBIDDEN
    }
}

/** The rules every write must pass, on devices before writing and on the server before storing. */
object Rules {
    private const val CLOCK_SKEW = 10 * 60_000L

    fun clockAhead(v: Long, now: Long): Boolean = Hlc.millis(v) > now + CLOCK_SKEW

    fun deck(a: Access, stored: Deck?, d: Deck): ErrorCode? {
        if (!Ids.isValid(d.id)) return ErrorCode.INVALID
        if (d.name.isBlank() || d.name.length > 100 || !Positions.isValid(d.position) || d.color !in 0..7 ||
            (d.icon?.length ?: 0) > 16 || (d.language?.length ?: 0) > 35 || (d.description?.length ?: 0) > 2000 ||
            d.newPerDay !in 0..9999 || d.reviewsPerDay !in 0..99999 || d.retention !in 70..99 ||
            d.unlockAt !in 50..100 || d.unlockAt % 10 != 0
        ) {
            return ErrorCode.INVALID
        }
        if (stored != null) {
            if (stored.ownerId == null) return ErrorCode.READ_ONLY
            if (stored.ownerId != d.ownerId) return ErrorCode.OWNER_MISMATCH
            a.canWrite(stored.id)?.let { return it }
            // A borrower can't move or delete the deck lent to them, nor take a deck out of it.
            val borrowed = stored.ownerId != a.actor
            val movedOrDeleted = d.parentId != stored.parentId || d.deletedAt != null
            if (borrowed && stored.id in a.lends && movedOrDeleted) return ErrorCode.FORBIDDEN
            if (borrowed && d.parentId == null && stored.parentId != null) return ErrorCode.FORBIDDEN
        } else if (d.parentId == null) {
            if (d.ownerId != a.actor) return ErrorCode.OWNER_MISMATCH
        }
        if (d.parentId != null && (stored == null || d.parentId != stored.parentId)) {
            val parent = a.tree[d.parentId] ?: return ErrorCode.NOT_FOUND
            if (parent.ownerId != d.ownerId) return ErrorCode.OWNER_MISMATCH
            if (a.tree.dead(parent.id)) return ErrorCode.NOT_FOUND
            a.canWrite(parent.id)?.let { return it }
            val above = listOf(parent) + a.tree.ancestors(parent.id)
            if (above.any { it.id == d.id }) return ErrorCode.CYCLE
            if (above.size >= 32) return ErrorCode.INVALID
        }
        return null
    }

    fun note(a: Access, stored: Note?, n: Note, template: Template?): ErrorCode? {
        if (!Ids.isValid(n.id)) return ErrorCode.INVALID
        if (stored != null) {
            if (stored.ownerId == null) return ErrorCode.READ_ONLY
            if (stored.ownerId != n.ownerId) return ErrorCode.OWNER_MISMATCH
            a.canWrite(stored.deckId)?.let { return it }
        }
        val deck = a.tree[n.deckId] ?: return ErrorCode.NOT_FOUND
        if (a.tree.dead(deck.id) && n.deletedAt == null) return ErrorCode.NOT_FOUND
        if (deck.ownerId != n.ownerId) {
            return if (deck.ownerId == null) ErrorCode.READ_ONLY else ErrorCode.OWNER_MISMATCH
        }
        a.canWrite(deck.id)?.let { return it }
        template ?: return ErrorCode.NOT_FOUND
        if (!Templates.isBuiltin(template.id) && template.ownerId != n.ownerId) return ErrorCode.OWNER_MISMATCH
        // Anki's notes (kept 1:1) are read-only: neither their fields nor their note type change.
        val changed = stored != null && (stored.fields != n.fields || stored.templateId != n.templateId)
        if (template.kind == Templates.ANKI && changed) return ErrorCode.READ_ONLY
        if (Templates.fieldsProblem(template, n.fields) != null) return ErrorCode.INVALID
        return null
    }

    fun template(a: Access, stored: Template?, t: Template): ErrorCode? {
        if (!Ids.isValid(t.id) || Templates.isBuiltin(t.id)) return ErrorCode.INVALID
        if (stored != null) {
            if (stored.ownerId != t.ownerId) return ErrorCode.OWNER_MISMATCH
            if (stored.ownerId == null) return ErrorCode.READ_ONLY
            if (stored.kind != t.kind) return ErrorCode.INVALID
            if (stored.kind == Templates.ANKI && stored.def != t.def) return ErrorCode.READ_ONLY
        }
        if (t.ownerId != a.actor) return ErrorCode.FORBIDDEN
        return if (Templates.problem(t) != null) ErrorCode.INVALID else null
    }

    fun event(a: Access, e: Event, now: Long): ErrorCode? = when {
        e.userId != a.actor -> ErrorCode.FORBIDDEN
        !Ids.isValid(e.id) || !Ids.isValid(e.subjectId) -> ErrorCode.INVALID
        e.kind !in 1..9 || e.card !in 0..999 -> ErrorCode.INVALID
        e.kind == Learning.REVIEW && e.value !in 1..4 -> ErrorCode.INVALID
        e.kind == Learning.FLAG && e.value !in 0..7 -> ErrorCode.INVALID
        e.at > now + CLOCK_SKEW -> ErrorCode.CLOCK_AHEAD
        else -> null
    }

    fun settings(a: Access, s: Settings): ErrorCode? = when {
        s.userId != a.actor -> ErrorCode.FORBIDDEN
        s.grading != 2 && s.grading != 4 -> ErrorCode.INVALID
        s.theme !in 0..2 || s.textSize !in 50..200 || (s.timezone?.length ?: 0) > 64 -> ErrorCode.INVALID
        s.fsrsWeights != null && Fsrs.parse(s.fsrsWeights) == null -> ErrorCode.INVALID
        s.reminderAt != null && s.reminderAt !in 0..<24 * 60 -> ErrorCode.INVALID
        else -> null
    }
}
