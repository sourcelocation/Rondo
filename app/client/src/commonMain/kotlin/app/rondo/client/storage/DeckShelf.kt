package app.rondo.client.storage

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.rondo.client.db.Decks
import app.rondo.core.Deck
import app.rondo.core.ErrorCode
import app.rondo.core.Tree
import app.rondo.core.record.DeckRecord
import app.rondo.core.record.Record

/** The decks on this device: yours, and the ones lent to you. */
class DeckShelf internal constructor(private val store: Store) : Shelf {
    private val q get() = store.q

    /** Every deck, deleted ones included. */
    suspend fun all(): List<Deck> = q.decks().awaitAsList().map { it.deck() }

    suspend fun tree(): Tree = Tree(all())

    suspend fun get(id: String): Deck? = q.deck(id).awaitAsOneOrNull()?.deck()

    /** Keeps [deck], to push. */
    suspend fun save(deck: Deck) = put(deck, dirty = true)

    /** The ids of the decks [owner] owns. */
    suspend fun owned(owner: String): Set<String> = q.deckIdsOwnedBy(owner).awaitAsList().toSet()

    /** Takes [ids] off this device, not the server: decks that left a stream. */
    internal suspend fun drop(ids: Collection<String>) = ids.chunked(500).forEach { q.dropDecks(it) }

    private suspend fun put(d: Deck, dirty: Boolean) = q.putDeck(
        Decks(
            d.id, d.ownerId, d.parentId, d.name, d.position, d.icon, d.color.toLong(), d.description, d.language,
            d.newPerDay.toLong(), d.reviewsPerDay.toLong(), d.retention.toLong(), d.gated, d.unlockAt.toLong(), d.v,
            d.deletedAt, dirty,
        ),
    )

    private fun Decks.deck() = Deck(
        id, name, position, color.toInt(), new_per_day.toInt(), reviews_per_day.toInt(), retention.toInt(), gated,
        unlock_at.toInt(), v, owner_id, parent_id, icon, description, language, deleted_at,
    )

    /** Parents first, so the server knows a deck's parent before the deck. */
    override suspend fun unsent(): List<Record> {
        val tree = tree()
        return q.dirtyDecks().awaitAsList().map { it.deck() }.sortedBy { tree.ancestors(it.id).size }.map(::DeckRecord)
    }

    override suspend fun sent(records: List<Record>) {
        for (r in records) if (r is DeckRecord) q.cleanDeck(r.id, r.v)
    }

    override suspend fun keep(record: Record, effects: Effects) {
        if (record !is DeckRecord) return
        store.hlc.observe(record.v)
        val local = q.deck(record.id).awaitAsOneOrNull()
        if (local == null || !local.dirty || record.v >= local.v) put(record.deck(), dirty = false)
    }

    override suspend fun refused(record: Record, code: String, effects: Effects): String? {
        if (record !is DeckRecord) return null
        // A deck you may no longer see or change can't be read again.
        val unreachable = code in listOf(ErrorCode.FORBIDDEN, ErrorCode.READ_ONLY, ErrorCode.NOT_FOUND).map { it.value }
        if (!unreachable) effects.decks += record.id
        return record.name
    }
}
