package app.rondo.client

import app.rondo.client.anki.AnkiConversion
import app.rondo.client.services.FileFormat
import app.rondo.client.services.Imported
import app.rondo.core.files.infrastructure.Spreadsheet
import app.rondo.core.notes.domain.Note
import app.rondo.core.notes.domain.Tags
import app.rondo.core.notes.domain.Template
import app.rondo.core.study.domain.CardState
import app.rondo.core.study.domain.Days
import app.rondo.core.study.domain.Event
import app.rondo.core.study.domain.Memory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking

/** Decks out of Rondo and back in: Rondo files, CSV and Anki packages. */
class FilesTest {
    private val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3)

    private fun rondo() = runBlocking {
        Rondo.open(JvmPlatform("http://localhost:9", "", ":memory:", null), autoSync = false)
    }

    private suspend fun Rondo.notes(): List<Note> = store.notes.of(store.notes.ids()).filter { it.deletedAt == null }

    private suspend fun Rondo.card(note: Note): CardState = store.cards.of(note.id).single().state

    /** Imports a file as the import screen does, at the top level: with [progress] or not, made Rondo's with [convert]. */
    private suspend fun Rondo.import(bytes: ByteArray, progress: Boolean = true, convert: Boolean = false): Imported {
        val file = imports.open(bytes)!!
        val read = imports.progress(file.contents(), events = progress, answers = true)
        val contents = if (convert) AnkiConversion(read).contents else read
        return imports.add(file, imports.plan(contents, null))
    }

    private fun id() = Uuid.generateV7().toString()

    /** A deck with a sub-deck, three note types, a picture, tags and an answer. */
    private suspend fun Rondo.sample(): String {
        val deck = library.createDeck("Spanish")
        val verbs = library.createDeck("Verbs", deck.id)
        val picture = media.add(png, "image/png")
        val hola = library.newNote(
            deck.id,
            Template.BASIC.id,
            mapOf("1" to "**hola**", "2" to "hello"),
            Tags(listOf("greeting")),
        )
        library.newNote(verbs.id, Template.CLOZE.id, mapOf("1" to "yo {{c1::hablo}}, tú {{c2::hablas}}"))
        library.newNote(deck.id, Template.VOCABULARY.id, mapOf("1" to "perro", "3" to "dog"))
        library.newNote(deck.id, Template.OCCLUSION.id, mapOf("1" to "$picture\n1 0.1 0.1 0.2 0.2"))
        val now = Clock.System.now().toEpochMilliseconds()
        library.record(Event.Review(id(), store.me, hola.id, 1, now, 3))
        // A card in review: last seen five days ago, due in ten.
        val gato = library.newNote(deck.id, Template.BASIC.id, mapOf("1" to "gato", "2" to "cat"))
        val memory = Memory(Memory.REVIEW, 0, 15.0, 5.0, now + 10 * Days.DAY, now - 5 * Days.DAY, 3, 0)
        store.events.save(Event.Snapshot(id(), store.me, gato.id, 1, memory))
        store.cards.refresh(listOf(gato))
        return deck.id
    }

    @Test
    fun aRondoFileBringsTheDeckBackWithItsProgress() = runBlocking {
        val from = rondo()
        val file = from.exports.run(from.sample(), FileFormat.RONDO, progress = true)
        assertEquals("Spanish.rondo", file.name)

        val to = rondo()
        val imported = to.import(file.bytes)
        assertEquals(5, imported.notes)
        assertEquals(1, imported.media)
        val tree = to.store.decks.tree()
        assertEquals(listOf("Spanish"), tree.roots.map { it.name })
        assertEquals(listOf("Verbs"), tree.children(tree.roots.single().id).map { it.name })
        val hola = to.notes().single { it.fields["1"] == "**hola**" }
        assertEquals("greeting", hola.tags)
        assertEquals(to.store.me, hola.ownerId)
        assertTrue(to.card(hola).memory.state != Memory.NEW, "the answer came along")

        // Its answers came along: this device didn't have them.
        assertEquals(1, to.store.events.of(listOf(hola.id)).count { it is Event.Review })

        // Importing it again to the same place adds nothing: it's all there.
        val again = to.import(file.bytes)
        assertEquals(0, again.notes)
        assertEquals(5, again.repeated)
        assertEquals(listOf("Spanish"), to.store.decks.tree().roots.map { it.name })

        // Without progress, the same cards are new.
        val fresh = rondo()
        fresh.import(file.bytes, progress = false)
        assertEquals(Memory.NEW, fresh.card(fresh.notes().single { it.fields["1"] == "**hola**" }).memory.state)
    }

    @Test
    fun csvGoesOutAndComesBack() = runBlocking {
        val from = rondo()
        val deck = from.sample()
        val zipped = from.exports.run(deck, FileFormat.CSV, progress = false)
        // Four note types: a CSV each.
        val files = from.platform.unzip(zipped.bytes)
        assertEquals(setOf("Basic.csv", "Cloze.csv", "Vocabulary.csv", "Image occlusion.csv"), files.keys)

        val to = rondo()
        val target = to.library.createDeck("Imported")
        val sheet = Spreadsheet(files.getValue("Basic.csv").decodeToString())
        val basic = Template.BASIC
        val result = to.imports.spreadsheet(sheet, basic, sheet.rows.columns(basic), target)
        assertEquals(2, result.notes)
        val note = to.notes().single { it.fields["1"] == "**hola**" }
        assertEquals(mapOf("1" to "**hola**", "2" to "hello"), note.fields)
        assertEquals("greeting", note.tags)
        // A second time, it's all there already.
        assertEquals(0, to.imports.spreadsheet(sheet, basic, sheet.rows.columns(basic), target).notes)
    }

    @Test
    fun htmlCellsBecomeMarkup() = runBlocking {
        val to = rondo()
        val target = to.library.createDeck("From Anki")
        val sheet = Spreadsheet("#separator:tab\n#html:true\n<b>der Hund</b><br>the dog\tnoun")
        to.imports.spreadsheet(sheet, Template.BASIC, sheet.rows.columns(Template.BASIC), target)
        assertEquals("**der Hund**\nthe dog", to.notes().single().fields["1"])
    }

    @Test
    fun anAnkiPackageComesBackThroughAnkisImport() = runBlocking {
        val from = rondo()
        val deck = from.sample()
        val apkg = from.exports.run(deck, FileFormat.ANKI, progress = true)
        assertEquals("Spanish.apkg", apkg.name)
        System.getenv("RONDO_APKG_OUT")?.let { File(it).writeBytes(apkg.bytes) }

        val to = rondo()
        val imported = to.import(apkg.bytes, convert = true)
        assertEquals(5, imported.notes)
        val notes = to.notes()
        val tree = to.store.decks.tree()
        assertEquals(listOf("Spanish"), tree.roots.map { it.name })
        assertEquals(listOf("Verbs"), tree.children(tree.roots.single().id).map { it.name })
        val hola = notes.single { it.fields.values.any { v -> "hola" in v } }
        assertEquals("**hola**", hola.fields["1"])
        assertEquals("greeting", hola.tags)
        assertEquals(Template.BASIC.id, hola.templateId)
        assertTrue(to.card(hola).memory.state != Memory.NEW)
        val cloze = notes.single { it.templateId == Template.CLOZE.id }
        assertEquals("yo {{c1::hablo}}, tú {{c2::hablas}}", cloze.fields["1"])
        assertEquals(1, imported.media)
    }

    @Test
    fun yourOwnExportBesideItsDecksBringsWhereCardsStandNotTheAnswersAgain() = runBlocking {
        val r = rondo()
        val deck = r.sample()
        val file = r.exports.run(deck, FileFormat.RONDO, progress = true)
        val answers = { runBlocking { r.store.events.reviews().size } }
        assertEquals(1, answers())

        r.import(file.bytes)
        assertEquals(1, answers(), "the answer isn't counted twice")
        val copy = r.notes().single { it.fields["1"] == "**hola**" && it.deckId != deck }
        assertTrue(r.card(copy).memory.state != Memory.NEW)
    }
}
