package app.rondo.client

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.db.QueryResult
import app.rondo.core.DeckView
import app.rondo.core.NoteFilter
import app.rondo.core.Role
import app.rondo.core.Rules
import app.rondo.core.Search
import app.rondo.core.SqlFilter
import app.rondo.core.Workspace
import app.rondo.core.model.ErrorCode
import app.rondo.core.model.Event
import app.rondo.core.model.Note
import app.rondo.core.model.Rejection
import app.rondo.core.model.Rows
import app.rondo.core.model.Template
import kotlin.uuid.Uuid

/**
 * Notes matching a filter, from the device's database (Browse and the command-line MCP tools), in
 * [sort] order: added (newest first), changed (latest first), due (soonest first) or alpha.
 */
suspend fun Store.search(filter: NoteFilter, limit: Int, sort: String = "added"): List<Note> {
    val where = Search.where(filter, cardStates = true)
    val due = "(SELECT min(c.due) FROM card_state c WHERE c.note_id = n.id AND c.state > 0)"
    val order = when (sort) {
        "changed" -> "n.v DESC"
        "due" -> "$due IS NULL, $due"
        "alpha" -> "n.search_text"
        else -> "n.id DESC"
    }
    val sql = "SELECT n.id FROM notes n WHERE ${where.where} ORDER BY $order LIMIT ?"
    val ids = driver.executeQuery(null, sql, { c ->
        val ids = ArrayList<String>()
        while (c.next().value) ids += Uuid.fromByteArray(c.getBytes(0)!!).toString()
        QueryResult.Value(ids)
    }, where.args.size + 1) {
        where.args.forEachIndexed { i, a ->
            when (a) {
                is SqlFilter.Id -> bindBytes(i, Uuid.parse(a.value).toByteArray())
                is Long -> bindLong(i, a)
                else -> bindString(i, a.toString())
            }
        }
        bindLong(where.args.size, limit.toLong())
    }.await()
    val notes = ids.chunked(500).flatMap { q.notesById(it).awaitAsList() }.map { it.model() }.associateBy { it.id }
    return ids.mapNotNull(notes::get)
}

/** The command-line MCP client's view: this device's copy, synced like any other device. */
class LocalWorkspace(private val rondo: Rondo) : Workspace {
    private val store get() = rondo.store
    override val actor: String get() = store.me

    override suspend fun decks(): List<DeckView> {
        val access = rondo.library.access()
        val counts = store.q.progress().awaitAsList().associate { it.deck_id to it.notes.toInt() }
        return access.tree.byId.values.filter { !access.tree.dead(it.id) }.map {
            DeckView(
                it,
                access.tree.path(it.id),
                access.role(it.id),
                counts[it.id] ?: 0,
            )
        }
    }

    override suspend fun templates(): List<Template> = store.templates().values.toList()

    override suspend fun notes(filter: NoteFilter, limit: Int): List<Note> = store.search(filter, limit)

    override suspend fun notes(ids: List<String>): List<Note> = ids.chunked(500).flatMap {
        store.q.notesById(it).awaitAsList()
    }.map { it.model() }

    override suspend fun events(noteIds: List<String>): List<Event> = noteIds.chunked(500).flatMap {
        store.q.eventsIn(it).awaitAsList()
    }.map { it.model(store.me) }

    override suspend fun settingsTimezone(): String? = store.settings().timezone

    override suspend fun commit(rows: Rows): List<Rejection> {
        val rejected = ArrayList<Rejection>()
        fun check(entity: Rejection.Entity, id: String, code: ErrorCode?) = (code == null).also {
            if (!it) {
                rejected +=
                    Rejection(entity, id, code!!)
            }
        }
        for (d in rows.decks.orEmpty()) {
            val access = rondo.library.access()
            if (check(Rejection.Entity.DECK, d.id, Rules.deck(access, access.tree[d.id], d))) store.save(d)
        }
        val access = rondo.library.access()
        val saved = ArrayList<Note>()
        for (n in rows.notes.orEmpty()) {
            val template = store.template(n.templateId)
            if (check(Rejection.Entity.NOTE, n.id, Rules.note(access, store.note(n.id), n, template))) {
                store.save(n, template!!)
                saved += n
            }
        }
        store.refreshCards(saved)
        rondo.syncSoon(500)
        return rejected
    }

    override fun clock(): Long = store.hlc.next()
}
