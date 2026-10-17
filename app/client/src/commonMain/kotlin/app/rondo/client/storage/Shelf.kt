package app.rondo.client.storage

import app.rondo.core.kernel.infrastructure.Record

/**
 * One kind of thing this device keeps and syncs, as the syncer sees it: what's unsent, what was
 * sent, what arrived, what was refused. Each shelf turns its own rows into records and back.
 */
interface Shelf {
    /** What this device changed that the server doesn't have yet, in the order the server applies it. */
    suspend fun unsent(): List<Record>

    /** [records] reached the server: those of this shelf's are sent, unless they changed since. */
    suspend fun sent(records: List<Record>)

    /** Keeps a pulled [record] of this shelf's kind when it's newer than this device's; what follows goes in [effects]. */
    suspend fun keep(record: Record, effects: Effects)

    /**
     * The server refused [record], when it's this shelf's kind: what the sync issues panel calls it
     * (null: nothing to show), and what to read again in its place, in [effects].
     */
    suspend fun refused(record: Record, code: String, effects: Effects): String?
}

/** What records a sync pulled, or had refused, change beyond themselves. */
class Effects {
    /** Notes whose cards fold again: the note changed, or one of its cards' events arrived. */
    val notes = HashSet<String>()

    /** Decks and note types to read again from the server. */
    val decks = HashSet<String>()
    val templates = HashSet<String>()

    /** The learner's FSRS weights changed: every card folds again. */
    var weights = false
}
