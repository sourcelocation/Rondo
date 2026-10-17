package app.rondo.client.services

import app.rondo.client.storage.Store
import app.rondo.core.decks.domain.Deck
import app.rondo.core.kernel.domain.ErrorCode
import app.rondo.core.notes.domain.Note
import app.rondo.core.notes.domain.NoteFilter
import app.rondo.core.notes.domain.NoteSort
import app.rondo.core.notes.domain.Template
import app.rondo.core.study.domain.Event
import app.rondo.core.workspace.application.DeckView
import app.rondo.core.workspace.application.Workspace

/** The command-line MCP client's view: this device's copy, synced like any other device. */
class DeviceWorkspace(private val store: Store, private val library: Library, private val synced: () -> Unit) :
    Workspace {
    override val actor: String get() = store.me

    override suspend fun decks(): List<DeckView> {
        val access = library.access()
        val loads = library.loads()
        val tree = access.tree
        return tree.byId.values.filter { !tree.dead(it.id) }.map {
            DeckView(it, tree.path(it.id), access.role(it.id), loads[it.id]?.notes ?: 0)
        }
    }

    override suspend fun templates(): List<Template> = store.templates.all().values.toList()

    override suspend fun notes(filter: NoteFilter, limit: Int): List<Note> =
        store.notes.search(filter, limit, NoteSort.OLDEST)

    override suspend fun notes(ids: List<String>): List<Note> = store.notes.of(ids)

    override suspend fun events(noteIds: List<String>): List<Event> = store.events.of(noteIds)

    override suspend fun settingsTimezone(): String? = store.settings.get().timezone

    override suspend fun save(decks: List<Deck>, notes: List<Note>): Map<String, ErrorCode> =
        library.save(decks, notes).also { synced() }

    override fun clock(): Long = store.hlc.next()
}
