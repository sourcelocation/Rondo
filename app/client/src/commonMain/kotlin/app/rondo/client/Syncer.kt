package app.rondo.client

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.rondo.client.db.Lends
import app.rondo.client.db.Rejects
import app.rondo.client.db.Streams
import app.rondo.core.model.Deck
import app.rondo.core.model.ErrorCode
import app.rondo.core.model.FetchRequest
import app.rondo.core.model.Lend
import app.rondo.core.model.Note
import app.rondo.core.model.PullRequest
import app.rondo.core.model.PushRequest
import app.rondo.core.model.Rejection
import app.rondo.core.model.Rows
import app.rondo.core.model.StreamPage
import app.rondo.core.model.StreamPosition
import app.rondo.core.nowMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pushes this device's edits, then pulls each stream (`me` and every lent deck): newer rows win,
 * decks that left a stream are removed, decks that joined it are fetched, and refused edits are
 * kept for the sync issues panel.
 */
class Syncer(private val store: Store, private val net: Net, private val media: MediaFiles) {
    private val lock = Mutex()
    private val q = store.q

    suspend fun sync() = lock.withLock {
        online { media.upload() }
        online { push() }
        online { pull() }
    }

    private suspend fun push() {
        while (true) {
            val decks = q.dirtyDecks().awaitAsList().map { it.model() }
            val templates = q.dirtyTemplates().awaitAsList().map { it.model() }
            val notes = q.dirtyNotes().awaitAsList().map { it.model() }
            val events = q.unpushedEvents().awaitAsList().map { it.model(store.me) }
            val settings = store.settings().takeIf { q.settings().awaitAsOneOrNull()?.dirty == true }
            val smart = q.dirtySmartDecks().awaitAsList().map { it.model() }
            if (listOf(decks, templates, notes, events, smart).all { it.isEmpty() } && settings == null) return
            // Parents first, so the server knows a deck's parent before the deck.
            val tree = store.tree()
            val ordered = decks.sortedBy { tree.ancestors(it.id).size }
            val rows = Rows(ordered, templates, notes, events, listOfNotNull(settings), smart)
            val result = net.sync.push(PushRequest(rows)).ok()
            for (d in decks) q.cleanDeck(d.id, d.v)
            for (t in templates) q.cleanTemplate(t.id, t.v)
            for (n in notes) q.cleanNote(n.id, n.v)
            if (events.isNotEmpty()) q.markPushed(events.map { it.id })
            settings?.let { store.save(it, dirty = false) }
            for (sd in smart) q.cleanSmartDeck(sd.id, sd.v)
            val refetchDecks = HashSet<String>()
            val refetchTemplates = HashSet<String>()
            // A refused edit is kept, as this device had it, for the sync issues panel, and the server's
            // version is fetched in its place.
            for (r in result.rejected) {
                val (label, body) = when (r.entity) {
                    Rejection.Entity.DECK -> {
                        refetchDecks += r.id
                        decks.first { it.id == r.id }.let { it.name to json.encodeToString(it) }
                    }

                    Rejection.Entity.TEMPLATE -> {
                        refetchTemplates += r.id
                        templates.first { it.id == r.id }.let { it.name to json.encodeToString(it) }
                    }

                    Rejection.Entity.NOTE -> {
                        val note = notes.first { it.id == r.id }
                        refetchDecks += note.deckId
                        label(note) to json.encodeToString(note)
                    }

                    Rejection.Entity.EVENT -> {
                        q.markPushed(listOf(r.id))
                        "" to ""
                    }

                    Rejection.Entity.SETTINGS -> "" to ""

                    Rejection.Entity.SMART_DECK -> smart.first { it.id == r.id }.let {
                        it.name to
                            json.encodeToString(it)
                    }
                }
                if (label.isNotEmpty()) {
                    q.putReject(Rejects(r.id, r.entity.value, r.code.value, label, body, nowMillis()))
                }
                if (r.code == ErrorCode.FORBIDDEN || r.code == ErrorCode.READ_ONLY || r.code == ErrorCode.NOT_FOUND) {
                    refetchDecks.remove(r.id)
                }
            }
            refetch(refetchDecks, refetchTemplates)
            if (decks.size + notes.size + events.size < 500) return
        }
    }

    private suspend fun pull() {
        repeat(50) {
            if (q.streams().awaitAsList().none { it.key == "me" }) q.putStream(Streams("me", 0, null))
            val positions = q.streams().awaitAsList().map { StreamPosition(it.key, it.cursor, it.fingerprint) }
            val response = net.sync.pull(PullRequest(positions)).ok()
            lends(response.lends)
            var more = false
            for (page in response.streams) {
                apply(page)
                more = more || page.more || page.resync
            }
            if (!more) return
        }
    }

    private suspend fun lends(lends: List<Lend>) {
        val before = q.lends().awaitAsList().map { it.deck_id }.toSet()
        val now = lends.map { it.deckId }.toSet()
        for (gone in before - now) {
            remove(descendants(gone))
            q.dropStream(gone)
        }
        q.dropLends()
        for (l in lends) q.putLend(Lends(l.deckId, l.role.toLong(), l.ownerId, l.ownerName))
    }

    /** Every deck at and under [root] on this device, deleted ones included. */
    private suspend fun descendants(root: String): Set<String> {
        val byParent = store.decks().groupBy { it.parentId }
        val out = LinkedHashSet<String>()
        fun walk(id: String) {
            if (out.add(id)) byParent[id].orEmpty().forEach { walk(it.id) }
        }
        walk(root)
        return out
    }

    private suspend fun remove(decks: Collection<String>) {
        if (decks.isEmpty()) return
        val notes = decks.chunked(500).flatMap { q.notesIn(it, Long.MAX_VALUE).awaitAsList() }.map { it.id }
        notes.chunked(500).forEach { q.dropCards(it) }
        decks.chunked(500).forEach {
            q.dropNotesIn(it)
            q.dropDecks(it)
        }
        q.dropUnusedTemplates(store.me)
    }

    private suspend fun apply(page: StreamPage) {
        if (page.resync) {
            if (page.key != "me") remove(descendants(page.key))
            q.putStream(Streams(page.key, 0, null))
            return
        }
        apply(page.rows)
        page.deckIds?.let { ids ->
            val mine = page.key == "me"
            val local = if (mine) q.deckIdsOwnedBy(store.me).awaitAsList().toSet() else descendants(page.key)
            remove(local - ids.toSet())
            refetch(ids.toSet() - store.decks().map { it.id }.toSet(), emptySet())
        }
        q.putStream(Streams(page.key, page.cursor, page.fingerprint))
    }

    /** Stores pulled rows: a newer clock wins, a local edit not yet pushed keeps its place if newer. */
    suspend fun apply(rows: Rows) {
        val touched = ArrayList<Note>()
        var weightsChanged = false
        store.db.transaction {
            for (d in rows.decks.orEmpty()) {
                store.hlc.observe(d.v)
                val local = q.deck(d.id).awaitAsOneOrNull()
                if (local == null || !local.dirty || d.v >= local.v) store.save(d, dirty = false)
            }
            for (t in rows.templates.orEmpty()) {
                store.hlc.observe(t.v)
                val local = q.template(t.id).awaitAsOneOrNull()
                if (local == null || !local.dirty || t.v >= local.v) store.save(t, dirty = false)
            }
            val templates = store.templates()
            for (n in rows.notes.orEmpty()) {
                store.hlc.observe(n.v)
                val local = q.note(n.id).awaitAsOneOrNull()
                val template = templates[n.templateId]
                if (template != null && (local == null || !local.dirty || n.v >= local.v)) {
                    store.save(n, template, dirty = false)
                    touched += n
                }
            }
            for (e in rows.events.orEmpty()) store.save(e, pushed = true)
            for (sd in rows.smartDecks.orEmpty()) {
                store.hlc.observe(sd.v)
                val local = q.smartDeck(sd.id).awaitAsOneOrNull()
                if (local == null || !local.dirty || sd.v >= local.v) store.save(sd, dirty = false)
            }
            for (s in rows.settings.orEmpty()) {
                val local = q.settings().awaitAsOneOrNull()
                if (local == null || !local.dirty || s.v >= local.v) {
                    weightsChanged = local?.fsrs_weights != s.fsrsWeights
                    store.save(s, dirty = false)
                }
            }
        }
        val missing = rows.notes.orEmpty().map { it.templateId }.filter { store.template(it) == null }.toSet()
        if (missing.isNotEmpty()) refetch(emptySet(), missing)
        val eventNotes = rows.events.orEmpty().map { it.subjectId }.toSet() - touched.map { it.id }.toSet()
        val withEvents = eventNotes.chunked(500).flatMap { q.notesById(it).awaitAsList() }.map { it.model() }
        if (weightsChanged) store.refoldAll() else store.refreshCards(touched + withEvents)
    }

    private suspend fun refetch(decks: Set<String>, templates: Set<String>) {
        if (decks.isEmpty() && templates.isEmpty()) return
        val rows = net.sync.fetch(FetchRequest(decks.toList(), templates.toList())).ok()
        val returned = rows.decks.orEmpty().map(Deck::id).toSet()
        remove(decks - returned)
        apply(rows)
    }
}
