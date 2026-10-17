package app.rondo.core.decks.domain

import kotlin.math.ceil
import kotlin.math.min

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
