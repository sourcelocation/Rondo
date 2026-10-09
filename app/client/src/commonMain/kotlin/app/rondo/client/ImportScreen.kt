package app.rondo.client

import app.rondo.core.notes
import kotlin.js.JsExport

@JsExport
class ImportState internal constructor(
    val working: Boolean,
    val deckId: String?,
    val message: String?,
    val skipped: String?,
    val failed: Boolean,
    val decks: Array<Choice>,
)

/** Importing an Anki package. */
@JsExport
class ImportScreen internal constructor(override val app: App) : Screen<ImportState>() {
    override val live get() = false
    private var result = ImportState(false, null, null, null, false, emptyArray())

    override suspend fun load(): ImportState {
        val tree = app.rondo.store.tree()
        val mine = tree.byId.values.filter { it.ownerId == app.rondo.store.me && !tree.dead(it.id) }
        val decks = tree.choices(mine).toTypedArray()
        return ImportState(result.working, result.deckId, result.message, result.skipped, result.failed, decks)
    }

    /** Imports [bytes] (a .apkg or .colpkg) under [parentId], or at the top level. */
    fun run(bytes: ByteArray, translate: Boolean, history: Boolean, parentId: String?) = act {
        result = ImportState(true, null, null, null, false, emptyArray())
        show(load())
        result = try {
            val r = app.rondo.anki.run(bytes, translate, history, parentId)
            app.rondo.syncSoon()
            val skipped = r.skipped.takeIf { it > 0 }?.let(strings::skipped)
            ImportState(false, r.deck, strings.imported(r.notes, r.media), skipped, false, emptyArray())
        } catch (e: Refused) {
            ImportState(false, null, strings.importFailed, null, true, emptyArray())
        }
        show(load())
    }
}
