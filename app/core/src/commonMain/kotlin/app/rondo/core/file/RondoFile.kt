package app.rondo.core.file

import app.rondo.core.Deck
import app.rondo.core.History
import app.rondo.core.Template
import app.rondo.core.Tree
import app.rondo.core.record.DeckRecord
import app.rondo.core.record.EventRecord
import app.rondo.core.record.NoteRecord
import app.rondo.core.record.Record
import app.rondo.core.record.TemplateRecord
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * A Rondo file: [MANIFEST], then each table's records as a JSON array (`decks.json`,
 * `templates.json`, `notes.json`, `events.json`; an account's export has `settings.json` and
 * `smart_decks.json` too, which a deck doesn't need), and media under [MEDIA] named by hash. A deck's
 * export and an account's are the same format. Built-in note types are never in it.
 */
class RondoFile private constructor(
    private val given: Map<String, ByteArray>?,
    private val held: List<Contents>,
    private val heldMedia: List<MediaFile>,
    private val kind: String,
    private val exportedAt: Long,
) : DeckFile {
    /** The Rondo file in [files]: see [manifest]. */
    constructor(files: Map<String, ByteArray>) : this(files, emptyList(), emptyList(), "", 0)

    /** A Rondo file of [contents] and the [media] they show: a deck's export, or ([kind] "account") everything someone owns. */
    constructor(contents: List<Contents>, media: List<MediaFile>, exportedAt: Long, kind: String = DECK) :
        this(null, contents, media, kind, exportedAt)

    /** Its manifest; null when it isn't a Rondo file, is broken, or is a format newer than [FORMAT]. */
    val manifest: Manifest? by lazy {
        val files = given ?: return@lazy Manifest(FORMAT, kind, held.map { it.deck.id }, media(), exportedAt)
        runCatching { Record.json.decodeFromString(Manifest.serializer(), files.getValue(MANIFEST).decodeToString()) }
            .getOrNull()?.takeIf { it.format <= FORMAT }
    }

    override suspend fun contents(): List<Contents> {
        val files = given ?: return held
        if (manifest == null) return emptyList()
        val tree = Tree(table(files, DECKS, DeckRecord.serializer()).map { it.deck() })
        val templates = Template.builtins + table(files, TEMPLATES, TemplateRecord.serializer())
            .mapNotNull { it.template() }.filter { it.deletedAt == null }.associateBy { it.id }
        val history = History(table(files, EVENTS, EventRecord.serializer()).mapNotNull { it.event() })
        val notes = table(files, NOTES, NoteRecord.serializer()).map { it.note() }
            .filter { it.deletedAt == null && it.templateId in templates }
            .groupBy { it.deckId }
        fun contents(deck: Deck): Contents = Contents(
            deck,
            notes[deck.id].orEmpty().map { Contents.Entry(it, templates.getValue(it.templateId), history.of(it.id)) },
            tree.children(deck.id).map(::contents),
            history.of(deck.id),
        )
        // The decks at the top: those whose parent isn't in the file. Deleted ones, and those under them, stay out.
        return tree.byId.values.filter { it.parentId !in tree.byId && !tree.dead(it.id) }
            .sortedWith(compareBy({ it.position }, { it.id }))
            .map(::contents)
    }

    override suspend fun media(hash: String): MediaFile? {
        if (given == null) return heldMedia.firstOrNull { it.hash == hash }
        val bytes = given["$MEDIA$hash"]?.takeIf { Template.HASH.matches(hash) } ?: return null
        return MediaFile(hash, manifest?.media?.get(hash) ?: return null, bytes)
    }

    override suspend fun files(): Map<String, ByteArray> {
        given?.let { return it }
        val all = held.flatMap { it.all }
        val entries = all.flatMap { it.notes }
        val out = LinkedHashMap<String, ByteArray>()
        out[MANIFEST] = Record.json.encodeToString(Manifest.serializer(), manifest!!).encodeToByteArray()
        put(out, DECKS, DeckRecord.serializer(), all.map { DeckRecord(it.deck) })
        put(
            out,
            TEMPLATES,
            TemplateRecord.serializer(),
            entries.map { it.template }.filter { !it.builtin }.distinctBy { it.id }.map { TemplateRecord(it) },
        )
        put(out, NOTES, NoteRecord.serializer(), entries.map { NoteRecord(it.note) })
        put(
            out,
            EVENTS,
            EventRecord.serializer(),
            (
                all.flatMap {
                    it.events
                } + entries.flatMap { it.events }
                ).map { EventRecord(it) },
        )
        for (m in heldMedia) out["$MEDIA${m.hash}"] = m.bytes
        return out
    }

    private fun media(): Map<String, String> = heldMedia.associate { it.hash to it.mime }

    private fun <T> table(files: Map<String, ByteArray>, name: String, serializer: KSerializer<T>): List<T> =
        files[name]?.let { Record.json.decodeFromString(ListSerializer(serializer), it.decodeToString()) }.orEmpty()

    private fun <T> put(
        out: MutableMap<String, ByteArray>,
        name: String,
        serializer: KSerializer<T>,
        records: List<T>,
    ) {
        if (records.isNotEmpty()) {
            out[name] =
                Record.json.encodeToString(ListSerializer(serializer), records).encodeToByteArray()
        }
    }

    /**
     * `rondo.json`: the [format], what the file is ([kind] deck or account), the deck exported at the
     * top ([decks]), each media file's MIME type by hash, and when it was made.
     */
    @Serializable
    class Manifest(
        val format: Int,
        val kind: String,
        val decks: List<String>? = null,
        val media: Map<String, String>? = null,
        @SerialName("exported_at") val exportedAt: Long,
    )

    companion object {
        /** The newest format this reads, and the one it writes. */
        const val FORMAT = 1
        const val MANIFEST = "rondo.json"
        const val MEDIA = "media/"
        const val DECK = "deck"
        const val ACCOUNT = "account"
        private const val DECKS = "decks.json"
        private const val TEMPLATES = "templates.json"
        private const val NOTES = "notes.json"
        private const val EVENTS = "events.json"
    }
}
