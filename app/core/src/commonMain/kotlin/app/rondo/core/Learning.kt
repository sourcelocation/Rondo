package app.rondo.core

import app.rondo.core.model.Deck
import app.rondo.core.model.Event
import kotlin.math.ceil
import kotlin.math.min

/** One card's state for one learner, folded from their events. */
data class CardState(
    val memory: Memory = Memory(),
    /** First time the card left its learning steps; never undone except by a reset. */
    val learnedAt: Long? = null,
    /** First review: when the card stopped being new. */
    val introducedAt: Long? = null,
    val suspended: Boolean = false,
    val buriedAt: Long? = null,
    val flag: Int = 0,
)

/** Per deck (its own notes, not its sub-decks): what can be studied today and what was. */
data class DeckLoad(
    val newAvailable: Int = 0,
    val learningDue: Int = 0,
    val reviewDue: Int = 0,
    val newToday: Int = 0,
    val reviewsToday: Int = 0,
    val notes: Int = 0,
    val learnedNotes: Int = 0,
)

data class Counts(val new: Int, val learning: Int, val review: Int) {
    val total: Int get() = new + learning + review
}

/** A deck's place in its levels: [ring] is a whole percent, 100 only when true. */
data class Level(val open: Boolean, val ring: Int, val index: Int = 0, val of: Int = 0)

object Learning {
    const val REVIEW = 1
    const val RESET = 2
    const val SUSPEND = 3
    const val UNSUSPEND = 4
    const val BURY = 5
    const val FLAG = 6
    const val SNAPSHOT = 7
    const val VOID = 8
    const val UNLOCK = 9

    /** Folds one card's events (any order) into its state. Stored due dates win over replayed ones. */
    fun fold(key: String, events: List<Event>, fsrs: Fsrs): CardState {
        val voided = events.filter { it.kind == VOID }.map { it.subjectId }.toSet()
        var s = CardState()
        for (e in events.filter { it.id !in voided && it.kind != VOID }.sortedWith(compareBy({ it.at }, { it.id }))) {
            s = when (e.kind) {
                REVIEW -> {
                    val replayed = fsrs.review(key, s.memory, e.value ?: 3, e.at)
                    val next = e.due?.let { replayed.copy(due = it) } ?: replayed
                    s.copy(
                        memory = next,
                        learnedAt = s.learnedAt ?: e.at.takeIf { next.state == Memory.REVIEW },
                        introducedAt = s.introducedAt ?: e.at,
                    )
                }

                RESET -> s.copy(memory = Memory(), learnedAt = null, introducedAt = null)

                SUSPEND -> s.copy(suspended = true)

                UNSUSPEND -> s.copy(suspended = false)

                BURY -> s.copy(buriedAt = e.at)

                FLAG -> s.copy(flag = e.value ?: 0)

                SNAPSHOT -> {
                    val state = e.value ?: Memory.NEW
                    val memory = Memory(
                        state = state,
                        stability = e.stability ?: 0.0,
                        difficulty = e.difficulty ?: 0.0,
                        due = e.due,
                        last = e.at,
                        reps = e.reps ?: 0,
                        lapses = e.lapses ?: 0,
                    )
                    val learned = state == Memory.REVIEW || state == Memory.RELEARNING
                    // Reviews before the snapshot (an import with its history) keep when the card started.
                    s.copy(
                        memory = memory,
                        learnedAt = if (learned) s.learnedAt ?: e.at else null,
                        introducedAt = if (state != Memory.NEW) s.introducedAt ?: e.at else null,
                    )
                }

                else -> s
            }
        }
        return s
    }

    /**
     * What studying [deckId] offers today: everything under it, new cards only from open decks,
     * capped by the deck's own limits.
     */
    fun counts(tree: Tree, deckId: String, load: (String) -> DeckLoad, levels: Map<String, Level>): Counts {
        val deck = tree[deckId] ?: return Counts(0, 0, 0)
        val decks = tree.subtree(deckId)
        val loads = decks.map { it.id to load(it.id) }
        val newAvailable = loads.filter { levels[it.first]?.open != false }.sumOf { it.second.newAvailable }
        val newLeft = (deck.newPerDay - loads.sumOf { it.second.newToday }).coerceAtLeast(0)
        val reviewLeft = (deck.reviewsPerDay - loads.sumOf { it.second.reviewsToday }).coerceAtLeast(0)
        val learning = loads.sumOf { it.second.learningDue }
        return Counts(min(newAvailable, newLeft), learning, min(loads.sumOf { it.second.reviewDue }, reviewLeft))
    }

    /**
     * What a set of cards picked by a filter offers today, from its [load] (new cards in open levels
     * only): the cards answered today count wherever they were studied.
     */
    fun counts(load: DeckLoad, newPerDay: Int, reviewsPerDay: Int): Counts = Counts(
        min(load.newAvailable, (newPerDay - load.newToday).coerceAtLeast(0)),
        load.learningDue,
        min(load.reviewDue, (reviewsPerDay - load.reviewsToday).coerceAtLeast(0)),
    )

    /**
     * Levels for every deck: under a gated parent, sub-decks open in order once the one before is
     * learned to the next one's requirement, or when [unlocked] says so. A locked level's ring is
     * its progress over all levels before it; an open deck's ring is how much of it is learned.
     * [justOpened] gets the decks that opened by their requirement without an unlock event yet.
     */
    fun levels(
        tree: Tree,
        load: (String) -> DeckLoad,
        unlocked: Set<String>,
        justOpened: MutableSet<String>? = null,
    ): Map<String, Level> {
        val totals = HashMap<String, Pair<Int, Int>>()
        fun total(d: Deck): Pair<Int, Int> = totals.getOrPut(d.id) {
            val own = load(d.id)
            tree.children(d.id).map(::total).fold(own.notes to own.learnedNotes) { a, b ->
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
            val kids = tree.children(d.id)
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
        tree.roots.forEach { visit(it, true) }
        return out
    }
}
