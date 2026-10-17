package app.rondo.client

import app.rondo.core.decks.infrastructure.DeckRecord
import app.rondo.core.kernel.domain.ErrorCode
import app.rondo.core.kernel.domain.Refused
import app.rondo.core.notes.domain.Tags
import app.rondo.core.notes.infrastructure.NoteRecord
import kotlin.js.JsExport
import kotlin.uuid.Uuid

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
    private val syncer get() = app.rondo.syncer

    override suspend fun load() = IssuesState(
        syncer.issues().map {
            val canCopy = it.code != "quota_exceeded" && it.code != "too_large" && it.record != null
            Issue(it.id, it.entity, it.label, strings.error(it.code), it.at.toDouble(), canCopy)
        }.toTypedArray(),
    )

    fun discard(id: String, entity: String) = act { syncer.discard(id, entity) }

    /** Your refused version as something of your own: a note in a deck you own, or a new deck. */
    fun keepCopy(id: String, entity: String) = act {
        val reject = syncer.issues().firstOrNull { it.id == id && it.entity == entity } ?: return@act
        val library = app.rondo.library
        when (val record = reject.record) {
            is NoteRecord -> {
                val mine = record.note()
                val access = library.access()
                val tree = library.tree()
                val deck = mine.deckId.takeIf { access.canWrite(it) == null && tree[it]?.ownerId == library.me }
                    ?: tree.roots.firstOrNull { it.ownerId == library.me && it.name == strings.recovered }?.id
                    ?: library.createDeck(strings.recovered).id
                var template = library.template(mine.templateId) ?: throw Refused(ErrorCode.NOT_FOUND)
                // Someone else's note type comes along as a copy of your own.
                if (!template.builtin && template.ownerId != library.me) {
                    template = library.saveTemplate(template.with(id = Uuid.generateV7().toString(), deletedAt = null))
                }
                library.newNote(deck, template.id, mine.fields, Tags(mine.tags))
            }

            is DeckRecord -> {
                val mine = record.deck()
                val d = library.createDeck(mine.name)
                library.updateDeck(d.id) {
                    mine.copy(id = d.id, ownerId = d.ownerId, parentId = null, position = d.position, deletedAt = null)
                }
            }

            else -> Unit
        }
        syncer.discard(id, entity)
    }
}
