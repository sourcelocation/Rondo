package app.rondo.client.anki

import app.cash.sqldelight.db.SqlDriver
import app.rondo.client.Platform
import app.rondo.core.files.domain.Contents
import app.rondo.core.files.domain.MediaFile
import app.rondo.core.markup.domain.Markup
import app.rondo.core.markup.infrastructure.FieldHtml
import app.rondo.core.notes.domain.AnkiTemplate
import app.rondo.core.notes.domain.Block
import app.rondo.core.notes.domain.FieldDef
import app.rondo.core.notes.domain.Note
import app.rondo.core.notes.domain.Occlusion
import app.rondo.core.notes.domain.RondoTemplate
import app.rondo.core.notes.domain.Template
import app.rondo.core.study.domain.CardState
import app.rondo.core.study.domain.Days
import app.rondo.core.study.domain.Event
import app.rondo.core.study.domain.Fsrs
import app.rondo.core.study.domain.History
import app.rondo.core.study.domain.Memory
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okio.ByteString.Companion.encodeUtf8

/**
 * A deck written as an Anki package in the legacy format (`collection.anki21`, schema 11, a JSON
 * media list): frozen for years, and read by every Anki, AnkiDroid and AnkiMobile. Rondo's note
 * types become note types showing the same fields, markup becomes HTML, and with events each
 * card's schedule, FSRS memory and reviews come along. Image occlusion keeps its picture, not its
 * masks.
 */
internal class AnkiWriter(
    private val platform: Platform,
    private val contents: Contents,
    media: List<MediaFile>,
    private val fsrs: Fsrs,
    private val days: Days,
) {
    private val media = media.associateBy { it.hash }

    private class Model(val id: Long, val template: Template, val fields: List<FieldDef>, val names: List<String>)

    /** The package's files: the collection, the media list and the media, by name. */
    suspend fun files(): Map<String, ByteArray> {
        val now = Clock.System.now().toEpochMilliseconds()
        val ids = Unique()
        val root = contents.deck
        val all = contents.all
        val byId = all.associate { it.deck.id to it.deck }
        val entries = all.flatMap { it.notes }
        val progress = entries.any { it.events.isNotEmpty() }

        // Anki's deck names are paths: "Spanish::Verbs".
        fun path(id: String): List<String> {
            val d = byId.getValue(id)
            val name = d.name.replace("::", ":")
            return if (d.id == root.id || d.parentId !in byId) listOf(name) else path(d.parentId!!) + name
        }
        val deckIds = all.associate { it.deck.id to ids.next(millis(it.deck.id)) }

        val files = LinkedHashMap<String, String>()
        fun file(hash: String): String? {
            files[hash]?.let { return it }
            val m = media[hash] ?: return null
            return "$hash.${EXTENSIONS[m.mime] ?: "bin"}".also { files[hash] = it }
        }

        val models = entries.map { it.template }.distinctBy { it.id }.associate { t ->
            val names = HashSet<String>()
            val unique = t.fields.map { f ->
                val base = f.name.replace(Regex("[:{}\"]"), " ").trim().ifEmpty { "Field ${f.id}" }
                generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }
                    .first { names.add(it.lowercase()) }
            }
            t.id to Model(ids.next(now), t, t.fields, unique)
        }

        val firstDay = days.index(now) - 3650
        val created = days.start(firstDay) / 1000
        val db = platform.openSqlite()
        try {
            SCHEMA.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { run(db, it) }
            // A file being made: nothing to keep safe until it's done.
            run(db, "PRAGMA synchronous = OFF")
            val noteRows = ArrayList<List<Any?>>()
            val cardRows = ArrayList<List<Any?>>()
            val revlog = ArrayList<List<Any?>>()
            var position = 0L
            for (e in entries) {
                val n = e.note
                val model = models.getValue(e.template.id)
                val t = model.template
                val values = model.fields.map { f -> html(t, f, n.fields[f.id.toString()].orEmpty(), ::file) }
                val noteId = ids.next(millis(n.id))
                val sort = strip(values.firstOrNull().orEmpty())
                val checksum = sort.encodeUtf8().sha1().hex().take(8).toLong(16)
                val tags = n.tags.let { if (it.isEmpty()) "" else " $it " }
                val flds = values.joinToString("\u001f")
                noteRows += listOf(noteId, n.id, model.id, now / 1000, -1L, tags, flds, sort, checksum, 0L, "")

                val history = History(e.events)
                val reviews = reviews(e.events)
                for (card in cards(t, n)) {
                    val ord = ord(t, card) ?: continue
                    val cardId = ids.next(noteId)
                    val state = if (progress) history.card(n.id, card).state(fsrs) else CardState()
                    val deck = deckIds.getValue(n.deckId)
                    cardRows += card(cardId, noteId, deck, ord, state, ++position, firstDay, now)
                    // Anki's review log: the first answer is learning (0), the rest reviews (1).
                    for ((i, r) in reviews[card].orEmpty().withIndex()) {
                        val time = (r.durationMs ?: 0).coerceIn(0, 60_000).toLong()
                        revlog +=
                            listOf(
                                ids.next(
                                    r.at,
                                ),
                                cardId, -1L, r.rating.toLong(), 0L, 0L, 0L, time, minOf(i, 1).toLong(),
                            )
                    }
                }
            }
            insert(db, "notes", 11, noteRows)
            insert(db, "cards", 18, cardRows)
            insert(db, "revlog", 9, revlog)
            val decks = buildJsonObject {
                put("1", deck(1, "Default"))
                for (d in all.map { it.deck }) {
                    val id = deckIds.getValue(d.id)
                    put(id.toString(), deck(id, path(d.id).joinToString("::"), d.description.orEmpty()))
                }
            }
            val modelJson = buildJsonObject {
                for (m in models.values) put(m.id.toString(), model(m, deckIds.getValue(root.id), now))
            }
            insert(
                db,
                "col",
                13,
                listOf(
                    listOf(
                        1L, created, now, now, 11L, 0L, 0L, 0L,
                        conf(deckIds.getValue(root.id), position).toString(), modelJson.toString(), decks.toString(),
                        DECK_CONFIG, "{}",
                    ),
                ),
            )
        } catch (e: Throwable) {
            platform.sqliteBytes(db)
            throw e
        }
        val collection = platform.sqliteBytes(db)
        val zip = LinkedHashMap<String, ByteArray>()
        zip["collection.anki21"] = collection
        val list = buildJsonObject {
            for ((i, entry) in files.entries.withIndex()) {
                val bytes = media[entry.key]?.bytes ?: continue
                zip[i.toString()] = bytes
                put(i.toString(), entry.value)
            }
        }
        zip["media"] = list.toString().encodeToByteArray()
        return zip
    }

    /** When a UUIDv7 was made, in milliseconds: its first 48 bits. */
    private fun millis(id: String): Long = id.replace("-", "").take(12).toLong(16)

    /** Field [f]'s value [v] as Anki's HTML, media named by [file]. Anki's own note types keep theirs. */
    private fun html(t: Template, f: FieldDef, v: String, file: (String) -> String?): String {
        if (t is AnkiTemplate) {
            var html = v
            for (hash in MEDIA_REF.findAll(v).map { it.groupValues[1] }.toSet()) {
                file(hash)?.let { html = html.replace("rondo-media:$hash", it) }
            }
            return html
        }
        if (f.kind == FieldDef.Kind.TEXT) return FieldHtml(Markup(v)).html
        val name = (if (f.kind == FieldDef.Kind.OCCLUSION) Occlusion(v).image else v).takeIf { it.isNotEmpty() }
            ?.let { file(it) } ?: return ""
        return if (f.kind == FieldDef.Kind.AUDIO) "[sound:$name]" else "<img src=\"$name\">"
    }

    /** The answers among a note's [events], by card, oldest first; undone ones left out. */
    private fun reviews(events: List<Event>): Map<Int, List<Event.Review>> {
        val undone = events.filterIsInstance<Event.Void>().map { it.subjectId }.toSet()
        return events.filterIsInstance<Event.Review>().filter { it.id !in undone && it.rating in 1..4 }
            .sortedBy { it.at }.groupBy { it.card }
    }

    /** [n]'s cards that become Anki's: image occlusion keeps one, for its picture. */
    private fun cards(t: Template, n: Note): List<Int> {
        val all = t.cards(n)
        return if (occlusion(t)) all.take(1) else all
    }

    private fun occlusion(t: Template) = t is RondoTemplate && t.cards.singleOrNull()?.each != null && cloze(t) == null

    /** Card [card]'s template among Anki's: its cloze's number for a cloze note type. */
    private fun ord(t: Template, card: Int): Int? = when {
        clozeType(t) -> card - 1
        occlusion(t) -> 0
        t is RondoTemplate -> t.cards.indexOfFirst { it.id == card }
        t is AnkiTemplate -> t.cards.indexOfFirst { it.id == card }
        else -> -1
    }.takeIf { it >= 0 }

    private fun clozeType(t: Template) = cloze(t) != null || (t is AnkiTemplate && t.clozeField != null)

    /** The field a Rondo template makes one card per cloze of, if it does. */
    private fun cloze(t: Template): FieldDef? {
        val each = (t as? RondoTemplate)?.cards?.singleOrNull()?.each ?: return null
        return t.fields.firstOrNull { it.id == each && it.kind == FieldDef.Kind.TEXT }
    }

    private fun card(
        id: Long,
        note: Long,
        deck: Long,
        ord: Int,
        s: CardState,
        position: Long,
        firstDay: Long,
        now: Long,
    ): List<Any?> {
        val m = s.memory
        val due = m.due ?: now
        val span = ((due - (m.last ?: due)).toDouble() / Days.DAY).roundToLong()
        val (type, queue, dueValue, interval) = when (m.state) {
            Memory.LEARNING -> listOf(1L, 1L, due / 1000, 0L)
            Memory.RELEARNING -> listOf(3L, 1L, due / 1000, span.coerceAtLeast(1))
            Memory.REVIEW -> listOf(2L, 2L, days.index(due) - firstDay, span.coerceAtLeast(1))
            else -> listOf(0L, 0L, position, 0L)
        }
        val data = if (m.state == Memory.NEW || m.stability <= 0) {
            "{}"
        } else {
            buildJsonObject {
                put("s", (m.stability * 1000).roundToInt() / 1000.0)
                put("d", (m.difficulty * 1000).roundToInt() / 1000.0)
                m.last?.let { put("lrt", it / 1000) }
            }.toString()
        }
        return listOf(
            id, note, deck, ord.toLong(), now / 1000, -1L, type, if (s.suspended) -1L else queue, dueValue, interval,
            if (m.state == Memory.NEW) 0L else 2500L, m.reps.toLong(), m.lapses.toLong(), if (queue == 1L) 1L else 0L,
            0L, 0L, (s.flag and 7).toLong(), data,
        )
    }

    private fun deck(id: Long, name: String, description: String = "") = buildJsonObject {
        put("id", id)
        put("name", name)
        put("mod", 0)
        put("usn", 0)
        for (k in listOf("lrnToday", "revToday", "newToday", "timeToday")) {
            putJsonArray(k) {
                add(0)
                add(0)
            }
        }
        put("collapsed", false)
        put("browserCollapsed", false)
        put("desc", description)
        put("dyn", 0)
        put("conf", 1)
        put("extendNew", 0)
        put("extendRev", 0)
    }

    private fun conf(deck: Long, next: Long) = buildJsonObject {
        put("nextPos", next + 1)
        put("estTimes", true)
        putJsonArray("activeDecks") { add(deck) }
        put("sortType", "noteFld")
        put("timeLim", 0)
        put("sortBackwards", false)
        put("addToCur", true)
        put("curDeck", deck)
        put("newSpread", 0)
        put("dueCounts", true)
        put("collapseTime", 1200)
        put("schedVer", 2)
    }

    /** [m] as Anki's note type: its fields, and a template per card (Rondo's blocks as HTML). */
    private fun model(m: Model, deck: Long, now: Long): JsonObject {
        val t = m.template
        val name = m.fields.associate { it.id to m.names[m.fields.indexOf(it)] }
        val cloze = cloze(t)
        fun side(blocks: List<Block>): String = blocks.joinToString("\n") { b ->
            when (b) {
                Block.Divider -> "<hr id=answer>"

                is Block.Label -> "<div class=\"label\">${b.text.replace("<", "&lt;")}</div>"

                is Block.TypeIn -> name[b.id]?.let { "{{type:$it}}" }.orEmpty()

                is Block.Field -> {
                    val field = name[b.id]
                    val size = (b.size ?: Block.Size.NORMAL).value
                    when {
                        field == null -> ""

                        b.id == cloze?.id -> "<div>{{cloze:$field}}</div>"

                        // Shown only when filled, as Rondo's cards leave out empty fields.
                        else -> "{{#$field}}<div class=\"$size\">{{$field}}</div>{{/$field}}"
                    }
                }
            }
        }
        val templates = when (t) {
            is AnkiTemplate -> t.cards.mapIndexed { i, c ->
                Triple(c.name.ifBlank { "Card ${i + 1}" }, c.frontHtml, c.backHtml)
            }

            is RondoTemplate -> t.cards.mapIndexed { i, c ->
                Triple(c.name.ifBlank { "Card ${i + 1}" }, side(c.front), side(c.back))
            }
        }
        return buildJsonObject {
            put("id", m.id)
            put("name", t.name)
            put("type", if (clozeType(t)) 1 else 0)
            put("mod", now / 1000)
            put("usn", -1)
            put("sortf", 0)
            put("did", deck)
            put(
                "tmpls",
                buildJsonArray {
                    for ((i, tmpl) in templates.withIndex()) {
                        add(
                            buildJsonObject {
                                put("name", tmpl.first)
                                put("ord", i)
                                put("qfmt", tmpl.second)
                                put("afmt", tmpl.third)
                                put("bqfmt", "")
                                put("bafmt", "")
                                put("did", JsonNull)
                                put("bfont", "")
                                put("bsize", 0)
                            },
                        )
                    }
                },
            )
            put(
                "flds",
                buildJsonArray {
                    for ((i, n) in m.names.withIndex()) {
                        add(
                            buildJsonObject {
                                put("name", n)
                                put("ord", i)
                                put("sticky", false)
                                put("rtl", false)
                                put("font", "Arial")
                                put("size", 20)
                                putJsonArray("media") {}
                            },
                        )
                    }
                },
            )
            put("css", if (t is AnkiTemplate) t.css else CSS)
            put("latexPre", LATEX_PRE)
            put("latexPost", "\\end{document}")
            put("latexsvg", false)
            put(
                "req",
                buildJsonArray {
                    for (i in templates.indices) {
                        add(
                            buildJsonArray {
                                add(i)
                                add("any")
                                add(JsonArray(m.names.indices.map { JsonPrimitive(it) }))
                            },
                        )
                    }
                },
            )
            putJsonArray("tags") {}
            putJsonArray("vers") {}
        }
    }

    /** Ids Anki keeps as milliseconds: each one unused yet, at or after what it's asked for. */
    private class Unique {
        private val used = HashSet<Long>()

        fun next(from: Long): Long {
            var id = from
            while (!used.add(id)) id++
            return id
        }
    }

    private companion object {
        val MEDIA_REF = Regex("rondo-media:([0-9a-f]{64})")
        val TAG = Regex("<[^>]*>")

        /** A field's text as Anki sorts and compares it: without HTML. */
        fun strip(html: String) = html.replace(TAG, " ").replace("&nbsp;", " ").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&amp;", "&").replace(Regex("\\s+"), " ").trim()

        val EXTENSIONS = mapOf(
            "image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif", "image/webp" to "webp",
            "audio/mpeg" to "mp3", "audio/mp4" to "m4a", "audio/ogg" to "ogg", "audio/wav" to "wav",
            "audio/webm" to "webm",
        )

        const val CSS = """.card { font-family: arial; font-size: 20px; text-align: center; }
.large { font-size: 1.6em; }
.small { font-size: 0.8em; color: #666; }
.label { font-size: 0.8em; color: #666; }"""

        const val LATEX_PRE = "\\documentclass[12pt]{article}\n\\special{papersize=3in,5in}\n" +
            "\\usepackage[utf8]{inputenc}\n\\usepackage{amssymb,amsmath}\n\\pagestyle{empty}\n" +
            "\\setlength{\\parindent}{0in}\n\\begin{document}\n"

        /** Anki's default deck options, for the decks the package makes. */
        const val DECK_CONFIG = """{"1": {"id": 1, "name": "Default", "mod": 0, "usn": 0, "maxTaken": 60, "autoplay": true,
  "timer": 0, "replayq": true, "dyn": false,
  "new": {"bury": false, "delays": [1.0, 10.0], "initialFactor": 2500, "ints": [1, 4, 0], "order": 1, "perDay": 20},
  "lapse": {"delays": [10.0], "leechAction": 1, "leechFails": 8, "minInt": 1, "mult": 0.0},
  "rev": {"bury": false, "ease4": 1.3, "ivlFct": 1.0, "maxIvl": 36500, "perDay": 200, "hardFactor": 1.2}}}"""

        /** Anki's schema 11, as its legacy packages hold it. */
        const val SCHEMA = """
CREATE TABLE col (id integer primary key, crt integer not null, mod integer not null, scm integer not null,
  ver integer not null, dty integer not null, usn integer not null, ls integer not null, conf text not null,
  models text not null, decks text not null, dconf text not null, tags text not null);
CREATE TABLE notes (id integer primary key, guid text not null, mid integer not null, mod integer not null,
  usn integer not null, tags text not null, flds text not null, sfld integer not null, csum integer not null,
  flags integer not null, data text not null);
CREATE TABLE cards (id integer primary key, nid integer not null, did integer not null, ord integer not null,
  mod integer not null, usn integer not null, type integer not null, queue integer not null, due integer not null,
  ivl integer not null, factor integer not null, reps integer not null, lapses integer not null, left integer not null,
  odue integer not null, odid integer not null, flags integer not null, data text not null);
CREATE TABLE revlog (id integer primary key, cid integer not null, usn integer not null, ease integer not null,
  ivl integer not null, lastIvl integer not null, factor integer not null, time integer not null, type integer not null);
CREATE TABLE graves (usn integer not null, oid integer not null, type integer not null);
CREATE INDEX ix_notes_usn ON notes (usn);
CREATE INDEX ix_cards_usn ON cards (usn);
CREATE INDEX ix_revlog_usn ON revlog (usn);
CREATE INDEX ix_cards_nid ON cards (nid);
CREATE INDEX ix_cards_sched ON cards (did, queue, due);
CREATE INDEX ix_revlog_cid ON revlog (cid);
CREATE INDEX ix_notes_csum ON notes (csum)"""
    }

    private suspend fun run(db: SqlDriver, sql: String) {
        db.execute(null, sql, 0).await()
    }

    /** Inserts [rows] of [columns] values each, many to a statement: each one is a trip to the web's worker. */
    private suspend fun insert(db: SqlDriver, table: String, columns: Int, rows: List<List<Any?>>) {
        for (chunk in rows.chunked(900 / columns)) {
            val values = chunk.joinToString(",") { "(" + List(columns) { "?" }.joinToString(",") + ")" }
            db.execute(null, "INSERT INTO $table VALUES $values", chunk.size * columns) {
                var i = 0
                for (row in chunk) {
                    for (v in row) {
                        when (v) {
                            null -> bindString(i, null)
                            is Long -> bindLong(i, v)
                            is Int -> bindLong(i, v.toLong())
                            is Double -> bindDouble(i, v)
                            else -> bindString(i, v.toString())
                        }
                        i++
                    }
                }
            }.await()
        }
    }
}
