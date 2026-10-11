package app.rondo.core

import kotlin.math.ceil
import kotlin.math.min
import kotlin.uuid.Uuid

/** A deck: its place among its siblings, its look, and its limits a day. */
data class Deck(
    val id: String,
    val name: String,
    val position: String,
    val color: Int,
    val newPerDay: Int,
    val reviewsPerDay: Int,
    /** Percent. */
    val retention: Int,
    /** Sub-decks open in order. */
    val gated: Boolean,
    /** Percent of the deck before this one that must be learned. */
    val unlockAt: Int,
    val v: Long,
    /** Null: its author removed it, and it's read-only. */
    val ownerId: String? = null,
    val parentId: String? = null,
    val icon: String? = null,
    val description: String? = null,
    /** BCP 47. */
    val language: String? = null,
    val deletedAt: Long? = null,
) {
    /** A new deck, as every client makes them: 20 new cards and 200 reviews a day, 90% retention, levels off. */
    constructor(name: String, position: String, v: Long, ownerId: String?, parentId: String?) : this(
        id = Uuid.generateV7().toString(), name = name, position = position, color = 0, newPerDay = 20,
        reviewsPerDay = 200, retention = 90, gated = false, unlockAt = 100, v = v, ownerId = ownerId,
        parentId = parentId,
    )

    /** Why [by] may not make this the deck that was [stored] (null: a new one), or null. */
    fun check(by: Access, stored: Deck?): ErrorCode? {
        if (Uuid.parseOrNull(id) == null) return ErrorCode.INVALID
        if (name.isBlank() || name.length > 100 || !Position(position).valid || color !in 0..7 ||
            (icon?.length ?: 0) > 16 || (language?.length ?: 0) > 35 || (description?.length ?: 0) > 2000 ||
            newPerDay !in NEW_PER_DAY || reviewsPerDay !in REVIEWS_PER_DAY || retention !in RETENTION ||
            unlockAt !in 50..100 || unlockAt % 10 != 0
        ) {
            return ErrorCode.INVALID
        }
        if (stored != null) {
            if (stored.ownerId == null) return ErrorCode.READ_ONLY
            if (stored.ownerId != ownerId) return ErrorCode.OWNER_MISMATCH
            by.canWrite(stored.id)?.let { return it }
            // A borrower can't move or delete the deck lent to them, nor take a deck out of it.
            val borrowed = stored.ownerId != by.actor
            val movedOrDeleted = parentId != stored.parentId || deletedAt != null
            if (borrowed && stored.id in by.lends && movedOrDeleted) return ErrorCode.FORBIDDEN
            if (borrowed && parentId == null && stored.parentId != null) return ErrorCode.FORBIDDEN
        } else if (parentId == null) {
            if (ownerId != by.actor) return ErrorCode.OWNER_MISMATCH
        }
        if (parentId != null && (stored == null || parentId != stored.parentId)) {
            val parent = by.tree[parentId] ?: return ErrorCode.NOT_FOUND
            if (parent.ownerId != ownerId) return ErrorCode.OWNER_MISMATCH
            if (by.tree.dead(parent.id)) return ErrorCode.NOT_FOUND
            by.canWrite(parent.id)?.let { return it }
            val above = listOf(parent) + by.tree.ancestors(parent.id)
            if (above.any { it.id == id }) return ErrorCode.CYCLE
            if (above.size >= 32) return ErrorCode.INVALID
        }
        return null
    }

    companion object {
        /** How many new cards a day a deck (or a smart deck) can give. */
        val NEW_PER_DAY = 0..9999

        /** How many reviews a day a deck (or a smart deck) can give. */
        val REVIEWS_PER_DAY = 0..99999

        /** The retention a deck can aim for, in percent. */
        val RETENTION = 70..99
    }
}

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

    /** The live decks a new one under [parent] goes after: its children, or at the top level [owner]'s own. */
    fun siblings(parent: String?, owner: String): List<Deck> =
        if (parent == null) roots.filter { it.ownerId == owner } else children(parent)

    /** The deck right after [id] among its siblings, if any. */
    fun next(id: String): Deck? {
        val siblings = get(id)?.parentId?.let(::children) ?: roots
        return siblings.getOrNull(siblings.indexOfFirst { it.id == id } + 1)
    }

    /** The language [id] is in: its own, or the nearest deck's above it that has one. */
    fun language(id: String): String? = (listOfNotNull(get(id)) + ancestors(id)).firstNotNullOfOrNull { it.language }

    /**
     * What studying [deckId] offers today: everything under it, new cards only from open decks,
     * capped by the deck's own limits.
     */
    fun counts(deckId: String, load: (String) -> DeckLoad, levels: Map<String, Level>): Counts {
        val deck = get(deckId) ?: return Counts(0, 0, 0)
        val loads = subtree(deckId).map { it.id to load(it.id) }
        val newAvailable = loads.filter { levels[it.first]?.open != false }.sumOf { it.second.newAvailable }
        val newLeft = (deck.newPerDay - loads.sumOf { it.second.newToday }).coerceAtLeast(0)
        val reviewLeft = (deck.reviewsPerDay - loads.sumOf { it.second.reviewsToday }).coerceAtLeast(0)
        val learning = loads.sumOf { it.second.learningDue }
        return Counts(min(newAvailable, newLeft), learning, min(loads.sumOf { it.second.reviewDue }, reviewLeft))
    }

    /**
     * Levels for every deck: under a gated parent, sub-decks open in order once the one before is
     * learned to the next one's requirement, or when [unlocked] says so. A locked level's ring is
     * its progress over all levels before it; an open deck's ring is how much of it is learned.
     * [justOpened] gets the decks that opened by their requirement without an unlock event yet.
     */
    fun levels(
        load: (String) -> DeckLoad,
        unlocked: Set<String>,
        justOpened: MutableSet<String>? = null,
    ): Map<String, Level> {
        val totals = HashMap<String, Pair<Int, Int>>()
        fun total(d: Deck): Pair<Int, Int> = totals.getOrPut(d.id) {
            val own = load(d.id)
            children(d.id).map(::total).fold(own.notes to own.learnedNotes) { a, b ->
                (a.first + b.first) to (a.second + b.second)
            }
        }
        val out = HashMap<String, Level>()
        fun visit(d: Deck, open: Boolean) {
            val (notes, learned) = total(d)
            val ring = when {
                notes == 0 -> 0
                learned >= notes -> 100
                else -> min(99, ceil(100.0 * learned / notes).toInt())
            }
            out.getOrPut(d.id) { Level(open, ring) }
            val kids = children(d.id)
            if (!d.gated) {
                kids.forEach { visit(it, open) }
                return
            }
            // How many of each level's notes must be learned to open the next one.
            val req = kids.mapIndexed { i, k ->
                val next = kids.getOrNull(i + 1) ?: return@mapIndexed 0
                ceil(next.unlockAt / 100.0 * total(k).first).toInt()
            }
            var lastOpen = -1
            kids.forEachIndexed { k, kid ->
                val byRequirement = k == 0 || (lastOpen == k - 1 && total(kids[k - 1]).second >= req[k - 1])
                val isOpen = open && (byRequirement || kid.id in unlocked)
                if (isOpen && k > 0 && byRequirement && kid.id !in unlocked) justOpened?.add(kid.id)
                if (byRequirement || kid.id in unlocked) lastOpen = k
                if (isOpen) {
                    visit(kid, true)
                    out[kid.id] = out.getValue(kid.id).copy(index = k + 1, of = kids.size)
                } else {
                    val o = lastOpen.coerceAtLeast(0)
                    val need = (0 until k).sumOf { req[it] }
                    val have = (0 until k).sumOf { i -> if (i < o) req[i] else min(total(kids[i]).second, req[i]) }
                    val p = if (need == 0) 99 else min(99, ceil(100.0 * have / need).toInt())
                    visit(kid, false)
                    out[kid.id] = Level(false, p, k + 1, kids.size)
                }
            }
        }
        roots.forEach { visit(it, true) }
        return out
    }
}

/** Per deck (its own notes, not its sub-decks): what can be studied today and what was. */
data class DeckLoad(
    val newAvailable: Int = 0,
    val learningDue: Int = 0,
    val reviewDue: Int = 0,
    val newToday: Int = 0,
    val reviewsToday: Int = 0,
    val notes: Int = 0,
    val learnedNotes: Int = 0,
) {
    /**
     * What a set of cards picked by a filter offers today, within [newPerDay] and [reviewsPerDay]
     * (new cards in open levels only): the cards answered today count wherever they were studied.
     */
    fun counts(newPerDay: Int, reviewsPerDay: Int): Counts = Counts(
        min(newAvailable, (newPerDay - newToday).coerceAtLeast(0)),
        learningDue,
        min(reviewDue, (reviewsPerDay - reviewsToday).coerceAtLeast(0)),
    )
}

data class Counts(val new: Int, val learning: Int, val review: Int) {
    val total: Int get() = new + learning + review
}

/** A deck's place in its levels: [ring] is a whole percent, 100 only when true. */
data class Level(val open: Boolean, val ring: Int, val index: Int = 0, val of: Int = 0)

/**
 * A place among siblings, as a fractional index: a key that sorts between its neighbours, so moving
 * one row changes one row. [key] after [after] and before [before]; null means no neighbour there.
 */
class Position(val key: String) {
    constructor(after: String?, before: String?) : this(between(after, before))

    val valid: Boolean get() = VALID.matches(key) && !key.endsWith('0')

    override fun toString(): String = key

    private companion object {
        const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val VALID = Regex("^[0-9A-Za-z]{1,64}$")

        fun between(a: String?, b: String?): String {
            require(a == null || b == null || a < b) { "$a is not before $b" }
            val out = StringBuilder()
            var open = b == null
            var i = 0
            while (true) {
                val low = a?.getOrNull(i)?.let(DIGITS::indexOf) ?: 0
                val high = if (open) DIGITS.length else DIGITS.indexOf(b!![i])
                val mid = (low + high) / 2
                if (mid > low) return out.append(DIGITS[mid]).toString()
                out.append(DIGITS[low])
                if (high > low) open = true
                i++
            }
        }
    }
}
