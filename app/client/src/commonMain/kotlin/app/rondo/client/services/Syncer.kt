package app.rondo.client.services

import app.rondo.client.net.Net
import app.rondo.client.storage.Effects
import app.rondo.client.storage.Reject
import app.rondo.client.storage.Store
import app.rondo.client.storage.Streams
import app.rondo.core.decks.infrastructure.DeckRecord
import app.rondo.core.kernel.infrastructure.Record
import app.rondo.core.sync.infrastructure.Fetch
import app.rondo.core.sync.infrastructure.Fetched
import app.rondo.core.sync.infrastructure.Lend
import app.rondo.core.sync.infrastructure.Page
import app.rondo.core.sync.infrastructure.Pull
import app.rondo.core.sync.infrastructure.Pulled
import app.rondo.core.sync.infrastructure.Push
import app.rondo.core.sync.infrastructure.Pushed
import app.rondo.core.sync.infrastructure.Stream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pushes this device's changes, then pulls each stream (`me` and every lent deck): newer records
 * win, decks that left a stream are removed, decks that joined it are fetched, and refused changes
 * are kept for the sync issues panel. Each kind of record is its shelf's: this only loops.
 */
class Syncer(private val store: Store, private val net: Net, private val media: MediaFiles) {
    private val lock = Mutex()

    /** Changes made here that haven't reached the server yet. */
    suspend fun pending(): Int = store.pending()

    /** The changes the server refused, the latest first: for the sync issues panel. */
    suspend fun issues(): List<Reject> = store.rejects.all()

    /** Lets a refused change go. */
    suspend fun discard(id: String, entity: String) = store.rejects.drop(id, entity)

    suspend fun sync() = lock.withLock {
        media.upload()
        push()
        pull()
    }

    private suspend fun push() {
        while (true) {
            val records = store.synced.flatMap { it.unsent() }
            if (records.isEmpty()) return
            val pushed = net.post("/sync/push", Push(records)).read<Pushed>()
            store.synced.forEach { it.sent(records) }
            // A refused change is kept, as this device had it, and the server's version read in its place.
            val effects = Effects()
            val byId = records.associateBy { it.id }
            for (r in pushed.rejected) {
                val record = byId[r.id] ?: continue
                val label = store.synced.firstNotNullOfOrNull { it.refused(record, r.code, effects) }
                if (label != null) store.rejects.put(r.entity, record, r.code, label)
            }
            fetch(effects.decks, effects.templates)
            // A full batch means there may be more.
            if (records.size < BATCH) return
        }
    }

    private suspend fun pull() {
        repeat(50) {
            val pulled = net.post("/sync/pull", Pull(store.streams.all())).read<Pulled>()
            lends(pulled.lends)
            var more = false
            for (page in pulled.streams) {
                apply(page)
                more = more || page.more || page.resync
            }
            if (!more) return
        }
    }

    private suspend fun lends(lends: List<Lend>) {
        val now = lends.map { it.deckId }.toSet()
        for (gone in store.lends.all().map { it.deckId } - now) {
            remove(descendants(gone))
            store.streams.drop(gone)
        }
        store.lends.replace(lends)
    }

    /** Every deck at and under [root] on this device, deleted ones included. */
    private suspend fun descendants(root: String): Set<String> {
        val byParent = store.decks.all().groupBy { it.parentId }
        val out = LinkedHashSet<String>()
        fun walk(id: String) {
            if (out.add(id)) byParent[id].orEmpty().forEach { walk(it.id) }
        }
        walk(root)
        return out
    }

    /** Takes [decks], and what's in them, off this device. */
    private suspend fun remove(decks: Collection<String>) {
        if (decks.isEmpty()) return
        store.notes.dropIn(decks)
        store.decks.drop(decks)
        store.templates.dropUnused()
    }

    private suspend fun apply(page: Page) {
        if (page.resync) {
            if (page.key != Streams.ME) remove(descendants(page.key))
            store.streams.put(Stream(page.key, 0))
            return
        }
        apply(page.records)
        page.deckIds?.let { ids ->
            val local = if (page.key == Streams.ME) store.decks.owned(store.me) else descendants(page.key)
            remove(local - ids.toSet())
            fetch(ids.toSet() - store.decks.all().map { it.id }.toSet(), emptySet())
        }
        store.streams.put(Stream(page.key, page.cursor, page.fingerprint))
    }

    /** Keeps pulled [records], each by its shelf; then the note types they need, and their cards folded. */
    private suspend fun apply(records: List<Record>) {
        val effects = Effects()
        store.transaction {
            for (r in records) store.synced.forEach { it.keep(r, effects) }
        }
        fetch(emptySet(), effects.templates)
        if (effects.weights) store.cards.refoldAll() else store.cards.refresh(effects.notes)
    }

    /** Reads [decks] and [templates] whole again; decks the server no longer gives are removed. */
    private suspend fun fetch(decks: Set<String>, templates: Set<String>) {
        if (decks.isEmpty() && templates.isEmpty()) return
        val fetched = net.post("/sync/fetch", Fetch(decks.toList(), templates.toList())).read<Fetched>()
        remove(decks - fetched.records.filterIsInstance<DeckRecord>().map { it.id }.toSet())
        apply(fetched.records)
    }

    private companion object {
        /** The most notes, and the most events, a push sends at a time. */
        const val BATCH = 500
    }
}
