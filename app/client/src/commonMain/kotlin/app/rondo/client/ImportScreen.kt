package app.rondo.client

import app.rondo.client.anki.AnkiConversion
import app.rondo.client.anki.AnkiPackage
import app.rondo.client.anki.Suggestion
import app.rondo.client.anki.Suggestions
import app.rondo.client.services.FileFormat
import app.rondo.client.services.Imported
import app.rondo.client.services.Plan
import app.rondo.core.files.application.DeckFile
import app.rondo.core.files.application.Rows
import app.rondo.core.files.domain.Contents
import app.rondo.core.files.infrastructure.RondoFile
import app.rondo.core.files.infrastructure.Spreadsheet
import app.rondo.core.kernel.domain.ErrorCode
import app.rondo.core.kernel.domain.Refused
import app.rondo.core.notes.domain.AnkiTemplate
import app.rondo.core.notes.domain.FieldDef
import app.rondo.core.notes.domain.Note
import app.rondo.core.notes.domain.Template
import kotlin.js.JsExport
import kotlinx.coroutines.CancellationException

/** A CSV being mapped: how its file reads, and where each column goes. */
@JsExport
class CsvState internal constructor(
    /** What separates its cells: one of [Spreadsheet.SEPARATORS]. */
    val separator: String,
    val header: Boolean,
    /** Its cells are HTML (Anki's text export): they become markup. */
    val html: Boolean,
    /** Each column's title: the cell in its first row, or "Column 2". */
    val titles: Array<String>,
    /** The first rows that become notes. */
    val preview: Array<Array<String>>,
    /** How many rows become notes. */
    val rows: Int,
    val templateId: String,
    val templates: Array<Choice>,
    /** Where a column can go: the note type's fields, then Tags. "" leaves it out. */
    val targets: Array<Choice>,
    /** Where each column goes now: an id of [targets], or "". */
    val columns: Array<String>,
    /** The deck the notes go in; null: a new one, called [deckName]. */
    val deckId: String?,
    val deckName: String,
    /** The first row's first card, as it will show; null while no row makes one. */
    val sample: Sample?,
)

/** A deck an Anki package fills: its [path], whether it's one of yours already, and how many notes it gets. */
@JsExport
class AnkiDeckItem internal constructor(val path: Array<String>, val existing: Boolean, val notes: Int)

/** A note type in an Anki package, with its first card as it will show; [kept]: why it keeps Anki's look. */
@JsExport
class AnkiTypeItem internal constructor(val name: String, val notes: Int, val kept: String?, val sample: Sample?)

/**
 * A change offered on top of the package's conversion: [kind] title or large (a field, [name]) or
 * language (a deck, [name], in [language]: BCP 47); [chosen] when it's to be made.
 */
@JsExport
class SuggestionItem internal constructor(
    val index: Int,
    val kind: String,
    val name: String,
    val language: String?,
    val chosen: Boolean,
)

/**
 * What importing an Anki package will do, as set up: under [parentId] (null: the top level), with
 * Anki's look ([keepLook]) or Rondo's, and the [suggestions] chosen. [canConvert]: some note type
 * can be Rondo's. [repeated] notes are in your decks already, from an earlier import, and stay out.
 */
@JsExport
class AnkiState internal constructor(
    val keepLook: Boolean,
    val canConvert: Boolean,
    val parentId: String?,
    val notes: Int,
    val cards: Int,
    val media: Int,
    val reviews: Int,
    val repeated: Int,
    val decks: Array<AnkiDeckItem>,
    val types: Array<AnkiTypeItem>,
    val suggestions: Array<SuggestionItem>,
)

/**
 * The import screen: nothing open yet, a file being set up (in its [kind]), or what an
 * import did ([message]). [decks] are where an Anki package or Rondo file can go, or the decks a
 * CSV can fill. [fromAnki]: the CSV is Anki's text export, which a package beats.
 */
@JsExport
class ImportState internal constructor(
    val working: Boolean,
    val kind: FileFormat?,
    val file: String?,
    val csv: CsvState?,
    val anki: AnkiState?,
    val fromAnki: Boolean,
    val decks: Array<Choice>,
    val deckId: String?,
    val message: String?,
    val skipped: String?,
    val failed: Boolean,
)

/** One screen for every import: Anki packages, Rondo files and CSV, recognised by what's in them. */
@JsExport
class ImportScreen internal constructor(override val app: App) : Screen<ImportState>() {
    override val live get() = false
    private val library get() = app.rondo.library
    private val imports get() = app.rondo.imports
    private var working = false
    private var kind: FileFormat? = null
    private var file: String? = null
    private var opened: DeckFile? = null
    private var done: Done? = null

    /** The Anki package, read and converted, what's offered on top, and what importing it would do as set up. */
    private var anki: Anki? = null
    private var keepLook = false
    private var parentId: String? = null

    /** The CSV, its text and how it's read and mapped. */
    private var text = ""
    private var sheet: Spreadsheet? = null
    private var template: Template? = null
    private var columns: List<String?> = emptyList()
    private var deckId: String? = null
    private var deckName = ""

    private class Done(val deck: String?, val message: String, val skipped: String?, val failed: Boolean)

    /** An Anki package read: as Anki has it, made Rondo's, and what could go on top. */
    private class Anki(val pkg: AnkiPackage, val contents: List<Contents>) {
        val conversion = AnkiConversion(contents)
        val suggestions: List<Suggestion> = Suggestions(conversion.contents).all
        val chosen = suggestions.filter { it.likely }.toMutableSet()
        var plan: Plan? = null
    }

    override suspend fun load(): ImportState {
        val tree = library.tree()
        val access = library.access()
        val choices = if (kind == FileFormat.CSV) {
            tree.byId.values.filter { !tree.dead(it.id) && access.canWrite(it.id) == null }
        } else {
            tree.byId.values.filter { it.ownerId == library.me && !tree.dead(it.id) }
        }
        val d = done
        return ImportState(
            working, kind, file, csv(), ankiState(), sheet?.anki == true, choices(tree, choices).toTypedArray(),
            d?.deck, d?.message, d?.skipped, d?.failed == true,
        )
    }

    /** The package's decks as they'd be added: Anki's look or Rondo's, with the suggestions chosen. */
    private fun ankiContents(a: Anki, history: Boolean): List<Contents> {
        val base = if (keepLook) a.contents else a.conversion.contents
        val chosen = a.suggestions.filter { it in a.chosen }.fold(base) { c, s -> s.apply(c) }
        return imports.progress(chosen, events = true, answers = history)
    }

    private fun ankiState(): AnkiState? {
        val a = anki ?: return null
        val plan = a.plan ?: return null
        val contents = ankiContents(a, history = true)
        val added = plan.decks.map { it.id }.toSet()
        fun decks(c: Contents, above: List<String>): List<AnkiDeckItem> {
            val path = above + c.deck.name
            val placed = plan.placed.getValue(c.deck.id)
            val notes = plan.notes.count { it.first.deckId == placed }
            val here = if (notes >
                0
            ) {
                listOf(AnkiDeckItem(path.toTypedArray(), placed !in added, notes))
            } else {
                emptyList()
            }
            return here + c.decks.flatMap { decks(it, path) }
        }
        val types = plan.notes.groupBy { it.second.id }.values.map { notes ->
            val (note, t) = notes.first()
            val kept = if (keepLook) null else a.conversion.kept[plan.from[note.id]?.template?.id]
            AnkiTypeItem(t.name, notes.size, kept?.let(strings::keptAnki), sample(t, note))
        }
        val anki = a.contents.flatMap {
            it.all
        }.flatMap { it.notes }.map { it.template }.filterIsInstance<AnkiTemplate>()
        return AnkiState(
            keepLook, a.conversion.kept.size < anki.distinctBy { it.id }.size, parentId, plan.notes.size,
            plan.notes.sumOf { (n, t) -> t.cards(n).size }, plan.notes.flatMap { (n, t) -> t.media(n) }.toSet().size,
            a.pkg.reviews, plan.there, contents.flatMap { decks(it, emptyList()) }.toTypedArray(), types.toTypedArray(),
            a.suggestions.mapIndexed { i, s -> item(i, s, s in a.chosen) }.toTypedArray(),
        )
    }

    private fun item(index: Int, s: Suggestion, chosen: Boolean): SuggestionItem = when (s) {
        is Suggestion.Title -> SuggestionItem(index, "title", s.name, null, chosen)
        is Suggestion.Large -> SuggestionItem(index, "large", s.name, null, chosen)
        is Suggestion.Language -> SuggestionItem(index, "language", s.name, s.language, chosen)
    }

    /** What importing the package would do now; its sample cards' pictures and sounds shown meanwhile. */
    private suspend fun replan() {
        val a = anki ?: return
        val plan = imports.plan(ankiContents(a, history = true), parentId)
        a.plan = plan
        imports.show(
            a.pkg,
            plan.notes.groupBy {
                it.second.id
            }.values.flatMap { (first) -> first.second.media(first.first) },
        )
    }

    /** Sets up an Anki package's import: with Anki's look ([keepLook]) or Rondo's, under [parentId] or at the top level. */
    fun setAnki(keepLook: Boolean, parentId: String?) = act {
        this.keepLook = keepLook
        this.parentId = parentId
        try {
            replan()
        } catch (e: Refused) {
            this.parentId = null
            replan()
        }
        show(load())
    }

    /** Makes suggestion [index] or not. */
    fun setSuggestion(index: Int, chosen: Boolean) = act {
        val a = anki ?: return@act
        val s = a.suggestions.getOrNull(index) ?: return@act
        if (chosen) a.chosen += s else a.chosen -= s
        replan()
        show(load())
    }

    private suspend fun csv(): CsvState? {
        val sheet = sheet ?: return null
        val t = template ?: return null
        val rows = sheet.rows
        val templates = library.templates().values.filter(rows::fits).sortedBy { !it.builtin }
        val titles = List(rows.width) { i ->
            rows.cells.firstOrNull()?.getOrNull(i)?.takeIf { rows.header && it.isNotBlank() } ?: strings.column(i + 1)
        }
        val targets = t.fields.filter { it.kind != FieldDef.Kind.OCCLUSION }.map { Choice(it.id.toString(), it.name) } +
            Choice(Rows.TAGS, strings.tags)
        val first = rows.body.firstOrNull()?.let {
            rows.notes(listOf(it), columns, t, "", null, { 0 }, cell = imports.cell(sheet)).notes.firstOrNull()
        }
        return CsvState(
            sheet.separator.toString(), rows.header, sheet.html, titles.toTypedArray(),
            rows.body.take(PREVIEW).map { r ->
                List(rows.width) { r.getOrElse(it) { "" } }.toTypedArray()
            }.toTypedArray(),
            rows.body.size, t.id, templates.map { Choice(it.id, it.name) }.toTypedArray(), targets.toTypedArray(),
            columns.map { it.orEmpty() }.toTypedArray(), deckId, deckName, first?.let { sample(t, it) },
        )
    }

    /** [n]'s first card under [t], as it will show. */
    private fun sample(t: Template, n: Note): Sample? = samples(t, n).firstOrNull()

    /** Opens a file chosen or dropped: an Anki package, a Rondo file, or text as CSV. */
    fun open(bytes: ByteArray, name: String) = act {
        clear()
        file = name
        working = true
        show(load())
        try {
            when (val f = imports.open(bytes)) {
                null -> imports.text(bytes)?.let { read(it, name.substringBeforeLast('.')) }
                    ?: run { done = Done(null, strings.importFailed, null, true) }

                is AnkiPackage -> {
                    anki = Anki(f, f.contents())
                    opened = f
                    replan()
                    kind = FileFormat.ANKI
                }

                else -> {
                    opened = f
                    kind = FileFormat.RONDO
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A package Rondo can't read: damaged, or not quite what Anki writes.
            clear()
            done = Done(null, strings.importFailed, null, true)
        } finally {
            working = false
        }
        show(load())
    }

    /** Rows pasted rather than a file: Quizlet's export, a spreadsheet's cells, an assistant's answer. */
    fun paste(text: String) = act {
        clear()
        read(text, strings.pasted)
        show(load())
    }

    private suspend fun read(text: String, name: String) {
        this.text = text
        val sheet = runCatching { Spreadsheet(text, library.templates().values) }.getOrNull()
        if (sheet == null || sheet.rows.body.isEmpty()) {
            done = Done(null, strings.importFailed, null, true)
            return
        }
        kind = FileFormat.CSV
        deckName = name.take(100)
        use(sheet, sheet.rows.template(library.templates().values.toList()))
    }

    private fun use(sheet: Spreadsheet, t: Template) {
        this.sheet = sheet
        template = t
        columns = sheet.rows.columns(t)
    }

    /** The CSV read again; null when it can't be read that way (a quote left open). */
    private suspend fun reread(separator: Char, header: Boolean): Spreadsheet? =
        runCatching { Spreadsheet(text, library.templates().values, separator, header) }.getOrNull()

    /** Reads the CSV again with [separator] between cells. */
    fun setSeparator(separator: String) = act {
        val sheet = sheet ?: return@act
        use(reread(separator.single(), sheet.rows.header) ?: return@act, template!!)
        show(load())
    }

    /** Whether the CSV's first row names its columns rather than being a note. */
    fun setHeader(header: Boolean) = act {
        val sheet = sheet ?: return@act
        use(reread(sheet.separator, header) ?: return@act, template!!)
        show(load())
    }

    /** The note type its rows fill; columns go to its fields again. */
    fun setTemplate(id: String) = act {
        val sheet = sheet ?: return@act
        use(sheet, library.template(id)?.takeIf(sheet.rows::fits) ?: return@act)
        show(load())
    }

    /** Where column [index] goes: a field's id, tags, or "" to leave it out. */
    fun setColumn(index: Int, target: String) = act {
        columns =
            List(maxOf(columns.size, index + 1)) { i ->
                if (i ==
                    index
                ) {
                    target.ifEmpty { null }
                } else {
                    columns.getOrNull(i)
                }
            }
        show(load())
    }

    /** The deck the CSV's notes go in; null makes a new one, called [name]. */
    fun setDeck(id: String?, name: String) = act {
        deckId = id
        deckName = name.take(100)
        show(load())
    }

    /**
     * Imports what's open: an Anki package (with Rondo's look when [translate], its review
     * [history] or not) or a Rondo file ([history]: with your progress) under [parentId], or the
     * CSV as mapped.
     */
    fun run(translate: Boolean, history: Boolean, parentId: String?) = act {
        if (working) return@act
        working = true
        show(load())
        done = try {
            when (kind) {
                FileFormat.ANKI -> {
                    val a = anki!!
                    keepLook = !translate
                    val plan = imports.plan(ankiContents(a, history), parentId)
                    val cards = plan.notes.sumOf { (n, t) -> t.cards(n).size }
                    imported(imports.add(a.pkg, plan), cards)
                }

                FileFormat.RONDO -> {
                    val f = opened!!
                    val contents = imports.progress(f.contents(), events = history, answers = true)
                    imported(imports.add(f, imports.plan(contents, parentId)), null)
                }

                FileFormat.CSV -> importCsv()

                null -> null
            }.also { app.rondo.syncSoon() }
        } catch (e: Refused) {
            Done(null, if (e.code == ErrorCode.INVALID) strings.importFailed else app.problem(e), null, true)
        }
        if (done?.failed == false) clear(keepResult = true)
        working = false
        show(load())
    }

    private fun imported(r: Imported, cards: Int?): Done {
        val message = cards?.let { strings.importedAnki(r.notes, it, r.media) } ?: strings.imported(r.notes, r.media)
        val left = listOfNotNull(
            r.skipped.takeIf { it > 0 }?.let(strings::skipped),
            r.repeated.takeIf { it > 0 }?.let(strings::repeated),
        )
        return Done(r.deck, message, left.joinToString(" ").ifEmpty { null }, false)
    }

    private suspend fun importCsv(): Done {
        val sheet = sheet!!
        val t = template!!
        val deck = deckId?.let { library.tree()[it] } ?: library.createDeck(deckName.ifBlank { strings.pasted })
        val r = imports.spreadsheet(sheet, t, columns, deck)
        val left = r.skipped + r.refused
        return Done(deck.id, strings.importedNotes(r.notes), left.takeIf { it > 0 }?.let(strings::rowsLeftOut), false)
    }

    /** Back to choosing a file. */
    fun reset() = act {
        clear()
        show(load())
    }

    private fun clear(keepResult: Boolean = false) {
        kind = null
        file = null
        opened = null
        text = ""
        sheet = null
        template = null
        columns = emptyList()
        deckId = null
        deckName = ""
        anki = null
        keepLook = false
        parentId = null
        app.rondo.media.unshow()
        if (!keepResult) done = null
    }

    private companion object {
        const val PREVIEW = 5
    }
}
