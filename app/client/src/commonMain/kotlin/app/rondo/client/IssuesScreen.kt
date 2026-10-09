package app.rondo.client

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.rondo.core.Ids
import app.rondo.core.Tags
import app.rondo.core.Templates
import app.rondo.core.model.Deck
import app.rondo.core.model.ErrorCode
import app.rondo.core.model.Note
import kotlin.js.JsExport

@JsExport
class Issue internal constructor(
    val id: String,
    val entity: String,
    val label: String,
    val reason: String,
    val at: Double,
    val canCopy: Boolean,
)

@JsExport
class IssuesState internal constructor(val items: Array<Issue>)

/** Edits the server refused: save your version as a copy, or let it go. */
@JsExport
class IssuesScreen internal constructor(override val app: App) : Screen<IssuesState>() {
    private val store get() = app.rondo.store

    override suspend fun load() = IssuesState(
        store.q.rejects().awaitAsList().map {
            val canCopy = it.code != "quota_exceeded" && it.code != "too_large"
            Issue(it.id, it.entity, it.label, strings.error(it.code), it.at.toDouble(), canCopy)
        }.toTypedArray(),
    )

    fun discard(id: String, entity: String) = act { store.q.dropReject(id, entity) }

    /** Your refused version as something of your own: a note in a deck you own, or a new deck. */
    fun keepCopy(id: String, entity: String) = act {
        val reject = store.q.rejects().awaitAsList().firstOrNull { it.id == id && it.entity == entity } ?: return@act
        val library = app.rondo.library
        when (entity) {
            "note" -> {
                val mine = json.decodeFromString<Note>(reject.body)
                val access = library.access()
                val deck = mine.deckId.takeIf { access.canWrite(it) == null && store.tree()[it]?.ownerId == store.me }
                    ?: store.tree().roots.firstOrNull { it.ownerId == store.me && it.name == strings.recovered }?.id
                    ?: library.createDeck(strings.recovered).id
                var template = store.template(mine.templateId) ?: throw Refused(ErrorCode.NOT_FOUND)
                // Someone else's note type comes along as a copy of your own.
                if (!Templates.isBuiltin(template.id) && template.ownerId != store.me) {
                    template = library.saveTemplate(template.copy(id = Ids.new(), deletedAt = null))
                }
                library.newNote(deck, template.id, mine.fields, Tags.of(mine))
            }

            "deck" -> {
                val mine = json.decodeFromString<Deck>(reject.body)
                val d = library.createDeck(mine.name)
                library.updateDeck(d.id) {
                    mine.copy(id = d.id, ownerId = d.ownerId, parentId = null, position = d.position, deletedAt = null)
                }
            }

            else -> Unit
        }
        store.q.dropReject(id, entity)
    }
}
