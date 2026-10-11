package app.rondo.client.anki

import app.rondo.client.Platform
import app.rondo.client.storage.Derived
import app.rondo.core.AnkiCardDef
import app.rondo.core.AnkiTemplate
import app.rondo.core.Days
import app.rondo.core.Deck
import app.rondo.core.ErrorCode
import app.rondo.core.FieldDef
import app.rondo.core.Fsrs
import app.rondo.core.Note
import app.rondo.core.Refused
import app.rondo.core.file.Contents
import app.rondo.core.file.DeckFile
import app.rondo.core.file.MediaFile
import io.ktor.http.decodeURLPart
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import okio.ByteString.Companion.toByteString

/**
 * An Anki package (.apkg or .colpkg), read in either of Anki's formats, or written in the legacy
 * one every Anki reads. Read, it holds Anki's decks, note types and notes exactly as Anki has them
 * (shown 1:1), with each card's progress; turning them into Rondo's own is [AnkiConversion]'s.
 *
 * Each thing's id carries where it came from: a note's its guid, a deck's its path, a note type's
 * Anki's id. So the same package read again gives the same ids, and adding it again finds what's
 * there already.
 */
class AnkiPackage private constructor(
    private val platform: Platform,
    private val zip: Map<String, ByteArray>?,
    private val written: Contents?,
    private val writtenMedia: List<MediaFile>,
    private val fsrs: Fsrs?,
    private val days: Days?,
) : DeckFile {
    /** The package in [zip]'s files. */
    constructor(platform: Platform, zip: Map<String, ByteArray>) : this(platform, zip, null, emptyList(), null, null)

    /**
     * [contents] as a package, with [media]. Their events, when they have them, become where each
     * card stands (by [fsrs]) and Anki's review log, in [days] as the learner lives them.
     */
    constructor(platform: Platform, contents: Contents, media: List<MediaFile>, fsrs: Fsrs, days: Days) :
        this(platform, null, contents, media, fsrs, days)

    /** The entry holding each media file, by the name notes use. */
    private val entries: Map<String, String> by lazy { mediaNames(zip?.get("media")?.let(::unpack)) }

    /** The media notes show, by hash: the name each has in the package. */
    private val hashes = HashMap<String, String>()

    private var read: List<Contents>? = null

    /** How many answers the package's cards have. */
    var reviews = 0
        private set

    override suspend fun contents(): List<Contents> {
        read?.let { return it }
        val zip = zip ?: return listOfNotNull(written)
        val file = listOf("collection.anki21b", "collection.anki21", "collection.anki2").firstNotNullOfOrNull(zip::get)
            ?: throw Refused(ErrorCode.INVALID)
        val db = runCatching { platform.openSqlite(unpack(file)) }.getOrNull() ?: throw Refused(ErrorCode.INVALID)
        val c = try {
            AnkiDatabase(db).read()
        } finally {
            db.close()
        }
        return contents(c).also { read = it }
    }

    private fun contents(c: AnkiCollection): List<Contents> {
        val now = Clock.System.now().toEpochMilliseconds()
        // A note goes in the deck holding its first card.
        val cards = c.cards.groupBy { it.note }
        val deckOf = cards.mapValues { (_, list) -> list.minBy { it.ord }.deck }
        val used = c.notes.filter { it.id in deckOf && it.type in c.types }
        reviews = used.sumOf { n -> cards[n.id].orEmpty().sumOf { it.reviews.size } }

        // Media the notes show, by hash; the HTML then links to them as rondo-media:<hash>.
        val byRef = HashMap<String, String?>()
        for (n in used) {
            for (f in n.fields) {
                for (m in IMAGE.findAll(f) + SOUND.findAll(f)) {
                    val ref = m.groupValues.last()
                    if (ref in byRef) continue
                    byRef[ref] =
                        file(ref)?.let { (bytes, _) -> bytes.toByteString().sha256().hex().also { hashes[it] = ref } }
                }
            }
        }
        fun link(html: String): String {
            val sounds = SOUND.replace(html) { m ->
                byRef[m.groupValues[1]]?.let { "[sound:rondo-media:$it]" }
                    ?: m.value
            }
            return IMAGE.replace(sounds) { m ->
                val (_, attribute, quote, ref) = m.groupValues
                byRef[ref]?.let { "$attribute${quote}rondo-media:$it$quote" } ?: m.value
            }
        }

        // Note types as Anki's HTML, shown 1:1.
        val templates = c.types.filterKeys { k -> used.any { it.type == k } }.mapValues { (mid, k) ->
            val fields = k.fields.mapIndexed { i, name ->
                FieldDef(i + 1, name.ifBlank { "Field ${i + 1}" }, FieldDef.Kind.TEXT)
            }
            val defs = k.cards.mapIndexed { i, card ->
                AnkiCardDef(i + 1, card.name.trim().ifBlank { "Card ${i + 1}" }, card.front, card.back)
            }
            AnkiTemplate(
                id(mid, "type:$mid"),
                k.name.trim().take(100).ifBlank {
                    "Anki"
                },
                fields,
                if (k.cloze) defs.take(1) else defs,
                k.css,
            )
        }

        // Decks by path, every level of it; each holds the notes whose first card is in it.
        fun path(deck: Long) =
            c.decks[deck]?.path.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("Default") }
        val paths = used.map {
            path(deckOf.getValue(it.id))
        }.flatMap { p -> p.indices.map { p.subList(0, it + 1) } }.distinct()
        val deckIds = paths.associateWith { p ->
            val anki = c.decks.values.firstOrNull { it.path.map(String::trim) == p }
            id(anki?.id ?: (c.created * 1000), "deck:" + p.joinToString("\u001f"))
        }

        // Notes in the order Anki shows their new cards, so studying starts where it would have.
        val firstNew = c.cards.filter { it.type == 0 }.groupBy { it.note }.mapValues { (_, cs) -> cs.minOf { it.due } }
        val ordered = used.sortedWith(compareBy({ firstNew[it.id] ?: Long.MAX_VALUE }, { it.id }))
        val start = ordered.minOfOrNull { it.id }?.coerceIn(0, now) ?: now
        val entries = ordered.mapIndexed { i, s ->
            val t = templates.getValue(s.type)
            val fields = s.fields.take(t.fields.size).withIndex().filter { it.value.isNotBlank() }
                .associate { (index, html) -> "${index + 1}" to link(html) }
            val deck = deckIds.getValue(path(deckOf.getValue(s.id)))
            // Anki's own tags become what Rondo has instead: "marked" a mark, "leech" nothing.
            val marked = s.tags.has(MARKED)
            val note =
                Note(
                    id(
                        start + i,
                        "note:${s.guid.ifEmpty {
                            s.id.toString()
                        }}",
                    ),
                    deck,
                    t.id,
                    fields,
                    0,
                    tags = (
                        s.tags -
                            listOf(MARKED, LEECH)
                        ).text,
                )
            val events = cards[s.id].orEmpty().flatMap { it.events(note.id, it.ord + 1, marked, c.created, now) }
            Contents.Entry(note, t, events)
        }.groupBy { it.note.deckId }

        fun contents(p: List<String>, position: String): Contents {
            val anki = c.decks.values.firstOrNull { it.path.map(String::trim) == p }
            val description = anki?.description?.let { AnkiHtml(it).markup.trim().take(2000) }?.ifEmpty { null }
            val deck =
                Deck(
                    deckIds.getValue(
                        p,
                    ),
                    p.last().take(100), position, 0, 20, 200, 90, false, 100, 0, description = description,
                )
            val children = paths.filter { it.size == p.size + 1 && it.subList(0, p.size) == p }.sortedWith(ORDER)
            val keys = positions(children.size)
            return Contents(
                deck,
                entries[deck.id].orEmpty(),
                children.mapIndexed { i, child ->
                    contents(child, keys[i])
                },
            )
        }
        val tops = paths.filter { it.size == 1 }.sortedWith(ORDER)
        val keys = positions(tops.size)
        return tops.mapIndexed { i, p -> contents(p, keys[i]) }
    }

    override suspend fun media(hash: String): MediaFile? {
        writtenMedia.firstOrNull { it.hash == hash }?.let { return it }
        val (bytes, mime) = file(hashes[hash] ?: return null) ?: return null
        return MediaFile(hash, mime, bytes)
    }

    override suspend fun files(): Map<String, ByteArray> {
        zip?.let { return it }
        return AnkiWriter(platform, written!!, writtenMedia, fsrs!!, days!!).files()
    }

    /** A media file a note refers to as [ref] (a name, or a `data:` URI), with its type; null when it isn't one Rondo keeps. */
    private fun file(ref: String): Pair<ByteArray, String>? {
        val zip = zip ?: return null
        if (ref.startsWith("data:")) {
            val mime = ref.removePrefix("data:").substringBefore(';')
            val data =
                ref.substringAfter(";base64,", "").takeIf { it.isNotEmpty() && mime in TYPES.values } ?: return null
            return runCatching { Base64.decode(data) }.getOrNull()?.let { it to mime }
        }
        val raw = ref.replace("&amp;", "&")
        val name = if (raw in entries) raw else runCatching { raw.decodeURLPart() }.getOrDefault(raw)
        val mime = TYPES[name.substringAfterLast('.').lowercase()] ?: return null
        val bytes = zip[entries[name] ?: return null]?.let(::unpack) ?: return null
        return bytes to mime
    }

    /** The zip entry holding each media file, by the name notes use. */
    private fun mediaNames(raw: ByteArray?): Map<String, String> = when {
        raw == null -> emptyMap()

        raw.firstOrNull() == '{'.code.toByte() -> {
            val entries = Json.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()),
                raw.decodeToString(),
            )
            entries.entries.associate { (entry, name) -> name to entry }
        }

        else -> ProtoBuf.decodeFromByteArray(MediaList.serializer(), raw).entries.withIndex()
            .associate { (i, e) -> e.name to (e.zipName ?: i).toString() }
    }

    @Serializable
    private class MediaList(@ProtoNumber(1) val entries: List<MediaEntry> = emptyList())

    @Serializable
    private class MediaEntry(@ProtoNumber(1) val name: String = "", @ProtoNumber(255) val zipName: Int? = null)

    /** Media in the latest format, and its collection, are zstd-compressed. */
    private fun unpack(b: ByteArray): ByteArray =
        if (b.size > 4 && b.copyOfRange(0, 4).contentEquals(ZSTD)) platform.unzstd(b) else b

    /** A UUIDv7 dated [time], the rest made from [identity]: the same thing read again gets the same id. */
    private fun id(time: Long, identity: String): String = Derived(identity).at(time)

    /** [n] keys in order, as short as they can be: siblings' positions. */
    private fun positions(n: Int): List<String> {
        var width = 1
        while (pow(width) < n) width++
        return List(n) { i ->
            var v = i
            val digits = CharArray(width) { ' ' }
            for (d in width - 1 downTo 0) {
                digits[d] = DIGITS[v % DIGITS.length]
                v /= DIGITS.length
            }
            digits.concatToString() + "V"
        }
    }

    private fun pow(width: Int): Int = (1..width).fold(1) { a, _ -> a * DIGITS.length }

    private companion object {
        val ZSTD = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())
        const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

        /** Anki's order of decks: by name, case aside. */
        val ORDER = compareBy<List<String>>({ it.last().lowercase() }, { it.last() })

        /** Tags Anki adds itself: a mark on the note, and a note failed often. */
        const val MARKED = "marked"
        const val LEECH = "leech"
        val IMAGE = Regex("""(<img\b[^>]*?\bsrc\s*=\s*)(["']?)([^"'\s>]+)\2""", RegexOption.IGNORE_CASE)
        val SOUND = Regex("""\[sound:([^\]]+)\]""")

        /** The media Rondo keeps, by extension. */
        val TYPES = mapOf(
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
            "webp" to "image/webp",
            "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "ogg" to "audio/ogg", "oga" to "audio/ogg",
            "opus" to "audio/ogg", "wav" to "audio/wav", "webm" to "audio/webm",
        )
    }
}
