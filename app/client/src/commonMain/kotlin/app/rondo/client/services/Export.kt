package app.rondo.client.services

import app.rondo.client.Platform
import app.rondo.client.anki.AnkiPackage
import app.rondo.client.storage.Store
import app.rondo.core.Days
import app.rondo.core.Template
import app.rondo.core.file.Contents
import app.rondo.core.file.MediaFile
import app.rondo.core.file.RondoFile
import app.rondo.core.file.Spreadsheet
import kotlin.js.JsExport
import kotlin.time.Clock

/** What a deck can be written down as, and read from. */
@JsExport
enum class FileFormat {
    /** A Rondo file. */
    RONDO,

    /** An Anki package. */
    ANKI,

    /** A spreadsheet. */
    CSV,
    ;

    /** Whether it carries where your cards stand and your reviews: a spreadsheet doesn't. */
    val keepsProgress: Boolean get() = this != CSV
}

/** A file made for the person to keep: [bytes] to save as [name]. */
@JsExport
class Download(val name: String, val mime: String, val bytes: ByteArray)

/** A deck as a file: a Rondo file, a spreadsheet, or an Anki package. Anyone who can copy a deck can export it. */
class Export(
    private val platform: Platform,
    private val store: Store,
    private val library: Library,
    private val media: MediaFiles,
) {
    /** Deck [id] as [format]; with [progress] (when it [FileFormat.keepsProgress]), your reviews and where your cards stand. */
    suspend fun run(id: String, format: FileFormat, progress: Boolean): Download {
        val contents = library.open(id, events = progress && format.keepsProgress)
        val name = fileName(contents.deck.name)
        return when (format) {
            FileFormat.CSV -> spreadsheet(contents, name)

            FileFormat.ANKI -> {
                val settings = store.settings.get()
                val file =
                    AnkiPackage(platform, contents, media(contents), store.settings.fsrs(), Days(settings.timezone))
                Download("$name.apkg", "application/octet-stream", platform.zip(file.files()))
            }

            FileFormat.RONDO -> {
                val file = RondoFile(listOf(contents), media(contents), Clock.System.now().toEpochMilliseconds())
                Download("$name.rondo", "application/zip", platform.zip(file.files()))
            }
        }
    }

    /** The pictures and sounds [contents]' notes show. */
    private suspend fun media(contents: Contents): List<MediaFile> = contents.all.flatMap { c ->
        c.notes.flatMap { it.template.media(it.note) }
    }.toSet().mapNotNull { media.get(it) }

    /** One CSV file, or a zip of one per note type when the deck uses several. */
    private suspend fun spreadsheet(contents: Contents, name: String): Download {
        val byTemplate = contents.all.flatMap { it.notes }.groupBy { it.template.id }.values
            .map { entries -> entries.first().template to entries.map { it.note } }
        if (byTemplate.size <= 1) {
            val (t, notes) = byTemplate.firstOrNull() ?: (Template.BASIC to emptyList())
            return Download("$name.csv", "text/csv", Spreadsheet(t, notes).text.encodeToByteArray())
        }
        val used = HashSet<String>()
        val files = byTemplate.associate { (t, notes) ->
            val base = fileName(t.name)
            val file = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { used.add(it) }
            "$file.csv" to Spreadsheet(t, notes).text.encodeToByteArray()
        }
        return Download("$name.zip", "application/zip", platform.zip(files))
    }

    /** [name] as a file's name: without the characters file systems refuse. */
    private fun fileName(name: String): String = name.replace(UNSAFE, "_").trim().ifEmpty { "Rondo" }

    private companion object {
        val UNSAFE = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")
    }
}
