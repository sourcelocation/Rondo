package app.rondo.client.storage

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.rondo.client.db.Lends as LendRow
import app.rondo.client.db.Streams as StreamRow
import app.rondo.core.sync.infrastructure.Lend
import app.rondo.core.sync.infrastructure.Stream

/** Where this device is in each stream it pulls: its account's (`me`) and each lent deck's. */
class Streams internal constructor(private val store: Store) {
    private val q get() = store.q

    /** Every stream, `me` among them. */
    suspend fun all(): List<Stream> {
        val streams = q.streams().awaitAsList().map { Stream(it.key, it.cursor, it.fingerprint) }
        return if (streams.any { it.key == ME }) streams else streams + Stream(ME, 0)
    }

    suspend fun put(stream: Stream) = q.putStream(StreamRow(stream.key, stream.cursor, stream.fingerprint))

    suspend fun drop(key: String) = q.dropStream(key)

    companion object {
        /** The account's own stream: everything it owns, its settings and its events. */
        const val ME = "me"
    }
}

/** The decks lent to this account, and who lent them. */
class Lends internal constructor(private val store: Store) {
    private val q get() = store.q

    suspend fun all(): List<Lend> = q.lends().awaitAsList().map {
        Lend(it.deck_id, it.role.toInt(), it.owner_id, it.owner_name)
    }

    /** Each lent deck's role: 1 viewer, 2 editor. */
    suspend fun roles(): Map<String, Int> = all().associate { it.deckId to it.role }

    /** The decks lent now, in place of those before. */
    internal suspend fun replace(lends: List<Lend>) = store.transaction {
        q.dropLends()
        for (l in lends) q.putLend(LendRow(l.deckId, l.role.toLong(), l.ownerId, l.ownerName))
    }
}
