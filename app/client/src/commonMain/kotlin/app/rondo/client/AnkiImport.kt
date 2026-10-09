package app.rondo.client

import app.rondo.core.Days
import app.rondo.core.Decks
import app.rondo.core.Ids
import app.rondo.core.Learning
import app.rondo.core.Positions
import app.rondo.core.Tags
import app.rondo.core.Templates
import app.rondo.core.model.CardDef
import app.rondo.core.model.Deck
import app.rondo.core.model.ErrorCode
import app.rondo.core.model.Event
import app.rondo.core.model.FieldDef
import app.rondo.core.model.Note
import app.rondo.core.model.Rows
import app.rondo.core.model.Template
import app.rondo.core.model.TemplateDef
import app.rondo.core.nowMillis
import io.ktor.http.decodeURLPart
import kotlin.io.encoding.Base64
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber

/** What an import added: [deck] is its first top-level deck; [skipped] notes broke a rule. */
class Imported(val deck: String?, val notes: Int, val media: Int, val skipped: Int)

/**
 * Anki packages (.apkg, .colpkg) in the legacy format (collection.anki2 or .anki21, a JSON media
 * map) and the latest (zstd-compressed collection.anki21b, a protobuf media list). Note types stay
 * Anki's HTML, shown 1:1, or become Rondo templates ([translate]). Progress comes as where each
 * card stands, and with [history] as every review too.
 */
class AnkiImport(
    private val platform: Platform,
    private val store: Store,
    private val files: MediaFiles,
    private val library: Library,
) {
    private class Kind(
        val name: String,
        val cloze: Boolean,
        val fields: List<String>,
        val cards: List<Triple<String, String, String>>,
        val css: String,
    )
    private class Source(val id: Long, val kind: Long, val fields: List<String>, val tags: Tags)
    private class Card(
        val id: Long,
        val note: Long,
        val deck: Long,
        val ord: Int,
        val type: Int,
        val queue: Int,
        val due: Long,
        val ivl: Int,
        val factor: Int,
        val reps: Int,
        val lapses: Int,
        val flags: Int,
        val data: String,
        val mod: Long,
    )
    private class Review(val id: Long, val card: Long, val ease: Int, val time: Int)

    @Serializable
    private class MediaList(@ProtoNumber(1) val entries: List<MediaEntry> = emptyList())

    @Serializable
    private class MediaEntry(@ProtoNumber(1) val name: String = "", @ProtoNumber(255) val zipName: Int? = null)

    @Serializable
    private class KindConfig(@ProtoNumber(1) val kind: Int = 0, @ProtoNumber(3) val css: String = "")

    @Serializable
    private class CardConfig(@ProtoNumber(1) val front: String = "", @ProtoNumber(2) val back: String = "")

    suspend fun run(bytes: ByteArray, translate: Boolean, history: Boolean, parent: String? = null): Imported {
        val tree = store.tree()
        if (parent != null && tree[parent]?.ownerId != store.me) throw Refused(ErrorCode.FORBIDDEN)
        val zip = runCatching { platform.unzip(bytes) }.getOrNull() ?: throw Refused(ErrorCode.INVALID)
        val collection =
            listOf("collection.anki21b", "collection.anki21", "collection.anki2").firstNotNullOfOrNull(zip::get)
                ?: throw Refused(ErrorCode.INVALID)
        val names = mediaNames(zip["media"]?.let(::unpack))
        val db = platform.openSqlite(unpack(collection))
        val created: Long
        val kinds: Map<Long, Kind>
        val deckPaths: Map<Long, List<String>>
        val sources: List<Source>
        val cards: List<Card>
        val reviews: Map<Long, List<Review>>
        try {
            created = db.all("SELECT crt FROM col") { it.getLong(0)!! }.first()
            if (db.all("SELECT 1 FROM sqlite_master WHERE name = 'notetypes'") { 1 }.isEmpty()) {
                val (models, decks) = db.all("SELECT models, decks FROM col") {
                    it.getString(0).orEmpty() to it.getString(1).orEmpty()
                }.first()
                kinds = legacyKinds(models)
                deckPaths = json.parseToJsonElement(decks).jsonObject.entries.associate { (id, d) ->
                    id.toLong() to d.jsonObject["name"].str().split("::")
                }
            } else {
                val fields = db.all("SELECT ntid, name FROM fields ORDER BY ntid, ord") {
                    it.getLong(0)!! to it.getString(1)!!
                }.groupBy({ it.first }, { it.second })
                val sides = db.all("SELECT ntid, name, config FROM templates ORDER BY ntid, ord") { r ->
                    val config = proto<CardConfig>(r.getBytes(2))
                    r.getLong(0)!! to Triple(r.getString(1)!!, config.front, config.back)
                }.groupBy({ it.first }, { it.second })
                kinds = db.all("SELECT id, name, config FROM notetypes") { r ->
                    val id = r.getLong(0)!!
                    val config = proto<KindConfig>(r.getBytes(2))
                    val name = r.getString(1)!!
                    id to Kind(name, config.kind == 1, fields[id].orEmpty(), sides[id].orEmpty(), config.css)
                }.toMap()
                deckPaths = db.all("SELECT id, name FROM decks") {
                    it.getLong(0)!! to it.getString(1)!!.split('\u001f')
                }.toMap()
            }
            sources = db.all("SELECT id, mid, flds, tags FROM notes") { r ->
                Source(
                    r.getLong(0)!!,
                    r.getLong(1)!!,
                    r.getString(2).orEmpty().split('\u001f'),
                    Tags.parse(r.getString(3)),
                )
            }
            val columns = "id, nid, did, odid, ord, type, queue, due, odue, ivl, factor, reps, lapses, flags, data, mod"
            cards = db.all("SELECT $columns FROM cards") { r ->
                fun n(i: Int) = r.getLong(i) ?: 0L
                // A card in a filtered deck keeps its home deck and due in odid and odue.
                val moved = n(3) != 0L
                Card(
                    id = n(0), note = n(1), deck = if (moved) n(3) else n(2), ord = n(4).toInt(), type = n(5).toInt(),
                    queue = n(6).toInt(), due = if (moved && n(8) != 0L) n(8) else n(7), ivl = n(9).toInt(),
                    factor = n(10).toInt(), reps = n(11).toInt(), lapses = n(12).toInt(), flags = n(13).toInt(),
                    data = r.getString(14).orEmpty(), mod = n(15),
                )
            }
            val log = if (history) {
                "SELECT id, cid, ease, time FROM revlog WHERE ease BETWEEN 1 AND 4 ORDER BY id"
            } else {
                "SELECT max(id), cid, 0, 0 FROM revlog WHERE ease > 0 GROUP BY cid"
            }
            reviews = db.all(log) {
                Review(it.getLong(0)!!, it.getLong(1)!!, it.getLong(2)!!.toInt(), it.getLong(3)!!.toInt())
            }.groupBy { it.card }
        } finally {
            db.close()
        }

        val me = store.me
        val now = nowMillis()
        // Decks: the ones holding a note's first card, and all above them, ordered by name.
        val deckOf = HashMap<Long, List<String>>()
        for (c in cards.sortedBy { it.ord }) {
            deckOf.getOrPut(c.note) { deckPaths[c.deck].orEmpty().map { it.trim() }.ifEmpty { listOf("Default") } }
        }
        val decks = LinkedHashMap<List<String>, Deck>()
        val lastPosition = HashMap<String?, String>()
        val paths = deckOf.values.flatMap { p -> p.indices.map { p.subList(0, it + 1) } }.toSet()
        // Parents before children, then by name; new top-level decks go after the ones already there.
        val order = compareBy<List<String>>({ it.size }, { it.joinToString("\u0000").lowercase() })
        val existing = if (parent == null) tree.roots.filter { it.ownerId == me } else tree.children(parent)
        for (path in paths.sortedWith(order)) {
            val above = if (path.size == 1) parent else decks.getValue(path.dropLast(1)).id
            val after = lastPosition[above] ?: existing.lastOrNull()?.position.takeIf { path.size == 1 }
            val position = Positions.between(after, null).also { lastPosition[above] = it }
            decks[path] = Decks.new(path.last().take(100).ifBlank { "Deck" }, position, store.hlc.next(), me, above)
        }

        // Media the notes show, stored by hash; the HTML then links to them as rondo-media:<hash>.
        val used = sources.filter { it.id in deckOf && it.kind in kinds }
        val hashes = HashMap<String, String?>()
        for (s in used) {
            for (f in s.fields) {
                for (m in IMAGE.findAll(f) + SOUND.findAll(f)) {
                    val ref = m.groupValues.last()
                    if (ref !in hashes) hashes[ref] = media(ref, zip, names)
                }
            }
        }
        fun link(html: String): String {
            val sounds = SOUND.replace(html) { m ->
                hashes[m.groupValues[1]]?.let { "[sound:rondo-media:$it]" } ?: m.value
            }
            return IMAGE.replace(sounds) { m ->
                val (_, attribute, quote, ref) = m.groupValues
                hashes[ref]?.let { "$attribute${quote}rondo-media:$it$quote" } ?: m.value
            }
        }

        val templates = kinds.filterKeys { k -> used.any { it.kind == k } }.mapValues { (_, k) ->
            val fields = k.fields.mapIndexed { i, name ->
                FieldDef(i + 1, name.ifBlank { "Field ${i + 1}" }, FieldDef.Kind.TEXT)
            }
            val sides = k.cards.mapIndexed { i, (name, front, back) ->
                val label = name.ifBlank { "Card ${i + 1}" }
                CardDef(i + 1, label, emptyList(), emptyList(), ankiFront = front, ankiBack = back)
            }
            val def = TemplateDef(fields, if (k.cloze) sides.take(1) else sides, k.css)
            Template(Ids.new(), k.name.take(100).ifBlank { "Anki" }, Templates.ANKI, def, store.hlc.next(), me)
        }

        // Notes in the order Anki shows their new cards, so studying starts where it would have.
        val firstNew = cards.filter { it.type == 0 }.groupBy { it.note }.mapValues { (_, c) -> c.minOf { it.due } }
        val ordered = used.sortedWith(compareBy<Source>({ firstNew[it.id] ?: Long.MAX_VALUE }, { it.id }))
        val start = ordered.minOfOrNull { it.id }?.coerceIn(0, now) ?: now
        val noteIds = HashMap<Long, String>()
        val notes = ordered.mapIndexed { i, s ->
            val t = templates.getValue(s.kind)
            val present = s.fields.take(t.def.fields.size).withIndex().filter { it.value.isNotBlank() }
            val fields = present.associate { (index, html) -> "${index + 1}" to link(html) }
            val deck = decks.getValue(deckOf.getValue(s.id)).id
            // Anki's own tags become what Rondo has instead: "marked" a mark, "leech" nothing.
            val tags = s.tags - listOf(MARKED, LEECH)
            Note(Ids.at(start + i), deck, t.id, fields, store.hlc.next(), me, tags = tags.text)
                .also { noteIds[s.id] = it.id }
        }
        val marked = used.filter { it.tags.list.any { t -> t.equals(MARKED, ignoreCase = true) } }.map { it.id }.toSet()

        val events = ArrayList<Event>()
        for (c in cards) {
            val note = noteIds[c.note] ?: continue
            val card = c.ord + 1
            val log = reviews[c.id].orEmpty()
            if (history) {
                for (r in log) {
                    val duration = r.time.coerceIn(0, 3_600_000)
                    events += Event(Ids.at(r.id), me, note, card, Learning.REVIEW, r.id, r.ease, duration)
                }
            }
            if (c.type in 1..3) {
                val due = if (c.due > 1_000_000_000) c.due * 1000 else (created + c.due * 86_400) * 1000
                // The snapshot stands just after the last review, or where the current interval began.
                val began = if (c.type == 2) due - c.ivl * Days.DAY else c.mod * 1000
                val at = minOf(now, log.lastOrNull()?.let { it.id + 1 } ?: began)
                val fsrs = runCatching { json.parseToJsonElement(c.data).jsonObject }.getOrNull()
                val stability = fsrs?.get("s").double() ?: c.ivl.coerceAtLeast(1).toDouble()
                // Without FSRS's memory, difficulty comes from Anki's ease (250% is average).
                val ease = c.factor.takeIf { it > 0 }?.let { (11 - (it / 1000.0 - 1) * 4).coerceIn(1.0, 10.0) }
                val difficulty = fsrs?.get("d").double() ?: ease ?: 5.0
                events += Event(
                    Ids.at(at), me, note, card, Learning.SNAPSHOT, at,
                    c.type, null, due, stability, difficulty, c.reps, c.lapses,
                )
            }
            val changed = minOf(now, c.mod * 1000)
            if (c.queue == -1) events += Event(Ids.at(changed), me, note, card, Learning.SUSPEND, changed)
            val flag = (c.flags and 7).takeIf { it != 0 } ?: if (c.note in marked) 1 else 0
            if (flag != 0) events += Event(Ids.at(changed), me, note, card, Learning.FLAG, changed, flag)
        }

        val rows = Rows(decks.values.toList(), templates.values.toList(), notes, events).let {
            if (translate) AnkiText.rows(it) else it
        }
        val skipped = library.add(rows)
        val first = decks.values.firstOrNull { it.parentId == parent }?.id
        return Imported(first, rows.notes.orEmpty().size - skipped, hashes.values.filterNotNull().toSet().size, skipped)
    }

    /** The zip entry holding each media file, by the name notes use. */
    private fun mediaNames(raw: ByteArray?): Map<String, String> = when {
        raw == null -> emptyMap()

        raw.firstOrNull() == '{'.code.toByte() -> {
            val entries = json.decodeFromString<Map<String, String>>(raw.decodeToString())
            entries.entries.associate { (entry, name) -> name to entry }
        }

        else -> proto<MediaList>(raw).entries.withIndex().associate { (i, e) -> e.name to (e.zipName ?: i).toString() }
    }

    private suspend fun media(ref: String, zip: Map<String, ByteArray>, names: Map<String, String>): String? {
        if (ref.startsWith("data:")) {
            val mime = ref.removePrefix("data:").substringBefore(';')
            val data =
                ref.substringAfter(";base64,", "").takeIf { it.isNotEmpty() && mime in TYPES.values } ?: return null
            return runCatching { Base64.decode(data) }.getOrNull()?.let { files.add(it, mime) }
        }
        val raw = ref.replace("&amp;", "&")
        val name = if (raw in names) raw else runCatching { raw.decodeURLPart() }.getOrDefault(raw)
        val mime = TYPES[name.substringAfterLast('.').lowercase()] ?: return null
        val bytes = zip[names[name] ?: return null]?.let(::unpack) ?: return null
        return files.add(bytes, mime)
    }

    private fun legacyKinds(models: String): Map<Long, Kind> =
        json.parseToJsonElement(models).jsonObject.entries.associate { (id, value) ->
            val m = value.jsonObject
            val ord = { e: JsonElement -> e.jsonObject["ord"].int() }
            val sides = m["tmpls"]?.jsonArray.orEmpty().sortedBy(ord).map {
                it.jsonObject.let { t -> Triple(t["name"].str(), t["qfmt"].str(), t["afmt"].str()) }
            }
            val fields = m["flds"]?.jsonArray.orEmpty().sortedBy(ord).map { it.jsonObject["name"].str() }
            id.toLong() to Kind(m["name"].str(), m["type"].int() == 1, fields, sides, m["css"].str())
        }

    /** Media in the latest format, and its collection, are zstd-compressed. */
    private fun unpack(b: ByteArray): ByteArray =
        if (b.size > 4 && b.copyOfRange(0, 4).contentEquals(ZSTD)) platform.unzstd(b) else b

    @OptIn(ExperimentalSerializationApi::class)
    private inline fun <reified T> proto(bytes: ByteArray?): T = ProtoBuf.decodeFromByteArray(bytes ?: ByteArray(0))

    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonElement?.int() = (this as? JsonPrimitive)?.intOrNull ?: 0

    private fun JsonElement?.double() = (this as? JsonPrimitive)?.doubleOrNull

    companion object {
        /** Tags Anki adds itself: a mark on the note, and a note failed often. */
        private const val MARKED = "marked"
        private const val LEECH = "leech"
        private val IMAGE = Regex("""(<img\b[^>]*?\bsrc\s*=\s*)(["']?)([^"'\s>]+)\2""", RegexOption.IGNORE_CASE)
        private val SOUND = Regex("""\[sound:([^\]]+)\]""")
        private val ZSTD = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())
        private val TYPES = mapOf(
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
            "webp" to "image/webp",
            "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "ogg" to "audio/ogg", "oga" to "audio/ogg",
            "opus" to "audio/ogg", "wav" to "audio/wav", "webm" to "audio/webm",
        )
    }
}
