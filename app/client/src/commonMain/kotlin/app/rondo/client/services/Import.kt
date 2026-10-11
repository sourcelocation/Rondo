package app.rondo.client.services

import app.rondo.client.Platform
import app.rondo.client.anki.AnkiHtml
import app.rondo.client.anki.AnkiPackage
import app.rondo.core.Deck
import app.rondo.core.Event
import app.rondo.core.Template
import app.rondo.core.file.Contents
import app.rondo.core.file.DeckFile
import app.rondo.core.file.RondoFile
import app.rondo.core.file.Spreadsheet

/**
 * What an import added: [deck] is its first top-level deck; [notes] and [media] were added,
 * [skipped] notes broke a rule, and [repeated] were there from an earlier import.
 */
class Imported(val deck: String?, val notes: Int, val media: Int, val skipped: Int, val repeated: Int = 0)

/** What a spreadsheet's import added: [notes], with [skipped] rows left out and [refused] notes the rules didn't take. */
class CsvImported(val notes: Int, val skipped: Int, val refused: Int)

/**
 * Importing files: each kind reads into decks in hand ([DeckFile]), and then they're added the same
 * way, wherever they came from. Where they go, and what's there already, is the library's.
 */
class Import(private val platform: Platform, private val library: Library, private val media: MediaFiles) {
    /** [bytes] as a file this imports: a Rondo file or an Anki package; null for text, or what isn't one. */
    suspend fun open(bytes: ByteArray): DeckFile? {
        if (!zipped(bytes)) return null
        val files = runCatching { platform.unzip(bytes) }.getOrNull() ?: return null
        return when {
            RondoFile.MANIFEST in files -> RondoFile(files)
            files.keys.any { it.startsWith("collection.anki") } -> AnkiPackage(platform, files)
            else -> null
        }
    }

    /** [bytes] as text, when they aren't an archive: a spreadsheet's. */
    fun text(bytes: ByteArray): String? = bytes.decodeToString().takeIf { !zipped(bytes) }

    private fun zipped(bytes: ByteArray) =
        bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    /** [contents] with their progress ([events]) or without; without [answers], cards come only where they stand. */
    fun progress(contents: List<Contents>, events: Boolean, answers: Boolean): List<Contents> = contents.map { c ->
        c.map(entry = { e ->
            Contents.Entry(
                e.note,
                e.template,
                if (!events) {
                    emptyList()
                } else {
                    e.events.filter {
                        answers ||
                            it !is Event.Review
                    }
                },
            )
        })
    }

    /** What adding [contents] under [parent] would do. */
    suspend fun plan(contents: List<Contents>, parent: String?): Plan = library.plan(contents, parent)

    /** Shows the media [hashes] of [file] before they're kept, for a preview's cards. */
    suspend fun show(file: DeckFile, hashes: Collection<String>) {
        for (hash in hashes) file.media(hash)?.let(media::show)
    }

    /** Keeps what [plan] adds, and the media of [file] its notes show. */
    suspend fun add(file: DeckFile, plan: Plan): Imported {
        var added = 0
        for (hash in plan.notes.flatMap { (n, t) -> t.media(n) }.toSet()) {
            if (file.media(hash)?.let { media.add(it) } == true) added++
        }
        val result = library.add(plan)
        return Imported(result.deck, result.notes, added, result.refused, result.there)
    }

    /**
     * Notes of [template] in [deck] from [sheet]'s rows, each column where [columns] sends it
     * ([Spreadsheet.columns]); HTML cells become markup. Rows whose first field is in the deck's
     * notes already are left out, and so are the rows that break a rule; both come back counted.
     */
    suspend fun spreadsheet(sheet: Spreadsheet, template: Template, columns: List<String?>, deck: Deck): CsvImported {
        val existing = library.repeats(template, deck.id).mapNotNull { template.key(it.fields) }.toSet()
        val made = sheet.notes(sheet.body, columns, template, deck.id, deck.ownerId, {
            0
        }, existing, cell = cell(sheet))
        val refused = library.save(emptyList(), made.notes).size
        return CsvImported(made.notes.size - refused, made.skipped.size, refused)
    }

    /** How [sheet]'s cells are read: Anki's HTML as markup, or as they are. */
    fun cell(sheet: Spreadsheet): (String) -> String = if (sheet.html) { s -> AnkiHtml(s).markup } else { s -> s }
}
