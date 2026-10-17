package app.rondo.client.anki

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.rondo.core.kernel.domain.DatedIds
import app.rondo.core.notes.domain.Tags
import app.rondo.core.study.domain.Days
import app.rondo.core.study.domain.Event
import app.rondo.core.study.domain.Memory
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber

/** An Anki collection as Anki keeps it, before anything is Rondo's: what a package holds. */
internal class AnkiCollection(
    /** When the collection began, in seconds: review cards' due days count from it. */
    val created: Long,
    val types: Map<Long, AnkiType>,
    val decks: Map<Long, AnkiDeck>,
    val notes: List<AnkiNote>,
    val cards: List<AnkiCard>,
)

/** A note type: its fields in order, each card's front and back, and the CSS they share. */
internal class AnkiType(
    val name: String,
    val cloze: Boolean,
    val fields: List<String>,
    val cards: List<AnkiCardType>,
    val css: String,
)

internal class AnkiCardType(val name: String, val front: String, val back: String)

/** A deck: its path from the top (Anki's `A::B`), and what its description says. */
internal class AnkiDeck(val id: Long, val path: List<String>, val description: String)

/** A note: [guid] is who it is wherever it's copied; [id] when it was made. */
internal class AnkiNote(val id: Long, val guid: String, val type: Long, val fields: List<String>, val tags: Tags)

/** A card where it stands, with its answers ([reviews], oldest first); one in a filtered deck is read in its home deck. */
internal class AnkiCard(
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
    val reviews: List<AnkiReview>,
) {
    /**
     * This card's progress as card [card] of note [note]: every answer, where it stands (a
     * snapshot, for a card past new: FSRS's memory, or Anki's ease and interval), suspended, and
     * its flag ([marked] notes get one). The learner's are given to them when it's added.
     */
    fun events(note: String, card: Int, marked: Boolean, created: Long, now: Long): List<Event> {
        val ids = DatedIds(0)
        val out = ArrayList<Event>()
        for (r in reviews) {
            out += Event.Review(ids.next(r.id), "", note, card, r.id, r.ease, r.time.coerceIn(0, 3_600_000))
        }
        if (type in 1..3) {
            val dueAt = if (due > 1_000_000_000) due * 1000 else (created + due * 86_400) * 1000
            // It stands just after the last answer, or where the current interval began.
            val began = if (type == 2) dueAt - ivl * Days.DAY else mod * 1000
            val at = minOf(now, reviews.lastOrNull()?.let { it.id + 1 } ?: began)
            val fsrs = runCatching { JSON.parseToJsonElement(data).jsonObject }.getOrNull()
            val stability = (fsrs?.get("s") as? JsonPrimitive)?.doubleOrNull ?: ivl.coerceAtLeast(1).toDouble()
            // Without FSRS's memory, difficulty comes from Anki's ease (250% is average).
            val ease = factor.takeIf { it > 0 }?.let { (11 - (it / 1000.0 - 1) * 4).coerceIn(1.0, 10.0) }
            val difficulty = (fsrs?.get("d") as? JsonPrimitive)?.doubleOrNull ?: ease ?: 5.0
            val memory = Memory(type, 0, stability, difficulty, dueAt, at, reps, lapses)
            out += Event.Snapshot(ids.next(at), "", note, card, memory)
        }
        val changed = minOf(now, mod * 1000)
        if (queue == -1) out += Event.Suspend(ids.next(changed), "", note, card, changed)
        val flag = (flags and 7).takeIf { it != 0 } ?: if (marked) 1 else 0
        if (flag != 0) out += Event.Flag(ids.next(changed), "", note, card, changed, flag)
        return out
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

internal class AnkiReview(val id: Long, val card: Long, val ease: Int, val time: Int)

/** An Anki collection's database, read: legacy (schema 11, JSON in `col`) or latest (schema 18). */
internal class AnkiDatabase(private val db: SqlDriver) {
    suspend fun read(): AnkiCollection {
        val latest = all("SELECT 1 FROM sqlite_master WHERE name = 'notetypes'") { 1 }.isNotEmpty()
        val schema = if (latest) Schema.Latest else Schema.Legacy
        val notes = all("SELECT id, guid, mid, flds, tags FROM notes") { r ->
            AnkiNote(
                r.getLong(0)!!,
                r.getString(1).orEmpty(),
                r.getLong(2)!!,
                r.getString(3).orEmpty().split('\u001f'),
                Tags(r.getString(4)),
            )
        }
        val reviews = all("SELECT id, cid, ease, time FROM revlog WHERE ease BETWEEN 1 AND 4 ORDER BY id") {
            AnkiReview(it.getLong(0)!!, it.getLong(1)!!, it.getLong(2)!!.toInt(), it.getLong(3)!!.toInt())
        }.groupBy { it.card }
        val columns = "id, nid, did, odid, ord, type, queue, due, odue, ivl, factor, reps, lapses, flags, data, mod"
        val cards = all("SELECT $columns FROM cards") { r ->
            fun n(i: Int) = r.getLong(i) ?: 0L
            // A card in a filtered deck keeps its home deck and due in odid and odue.
            val moved = n(3) != 0L
            AnkiCard(
                id = n(0), note = n(1), deck = if (moved) n(3) else n(2), ord = n(4).toInt(), type = n(5).toInt(),
                queue = n(6).toInt(), due = if (moved && n(8) != 0L) n(8) else n(7), ivl = n(9).toInt(),
                factor = n(10).toInt(), reps = n(11).toInt(), lapses = n(12).toInt(), flags = n(13).toInt(),
                data = r.getString(14).orEmpty(), mod = n(15), reviews = reviews[n(0)].orEmpty(),
            )
        }
        val created = all("SELECT crt FROM col") { it.getLong(0)!! }.first()
        return AnkiCollection(created, schema.types(this), schema.decks(this), notes, cards)
    }

    /** Every row of [sql]. */
    suspend fun <T> all(sql: String, row: (SqlCursor) -> T): List<T> = db.executeQuery(null, sql, { c ->
        QueryResult.Value(buildList { while (c.next().value) add(row(c)) })
    }, 0).await()

    /** Where note types and decks are kept: the one thing the two formats do differently. */
    private sealed interface Schema {
        suspend fun types(db: AnkiDatabase): Map<Long, AnkiType>

        suspend fun decks(db: AnkiDatabase): Map<Long, AnkiDeck>

        /** Up to schema 11: JSON in `col`. */
        data object Legacy : Schema {
            private suspend fun col(db: AnkiDatabase, column: String): JsonObject {
                val text = db.all("SELECT $column FROM col") { it.getString(0).orEmpty() }.first()
                return Json.parseToJsonElement(text).jsonObject
            }

            override suspend fun types(db: AnkiDatabase): Map<Long, AnkiType> =
                col(db, "models").entries.associate { (id, v) ->
                    val m = v.jsonObject
                    val ord = { e: JsonElement -> e.jsonObject["ord"].int() }
                    val cards = m["tmpls"]?.jsonArray.orEmpty().sortedBy(ord).map {
                        it.jsonObject.let { t -> AnkiCardType(t["name"].str(), t["qfmt"].str(), t["afmt"].str()) }
                    }
                    val fields = m["flds"]?.jsonArray.orEmpty().sortedBy(ord).map { it.jsonObject["name"].str() }
                    id.toLong() to AnkiType(m["name"].str(), m["type"].int() == 1, fields, cards, m["css"].str())
                }

            override suspend fun decks(db: AnkiDatabase): Map<Long, AnkiDeck> =
                col(db, "decks").entries.associate { (id, d) ->
                    val o = d.jsonObject
                    id.toLong() to AnkiDeck(id.toLong(), o["name"].str().split("::"), o["desc"].str())
                }

            private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

            private fun JsonElement?.int() = (this as? JsonPrimitive)?.intOrNull ?: 0
        }

        /** Schema 18: tables of their own, with protobuf settings. */
        data object Latest : Schema {
            @Serializable
            private class TypeConfig(@ProtoNumber(1) val kind: Int = 0, @ProtoNumber(3) val css: String = "")

            @Serializable
            private class CardConfig(@ProtoNumber(1) val front: String = "", @ProtoNumber(2) val back: String = "")

            @Serializable
            private class DeckKind(@ProtoNumber(1) val normal: DeckNormal? = null)

            @Serializable
            private class DeckNormal(@ProtoNumber(4) val description: String = "")

            @OptIn(ExperimentalSerializationApi::class)
            private inline fun <reified T> proto(bytes: ByteArray?): T =
                ProtoBuf.decodeFromByteArray(bytes ?: ByteArray(0))

            override suspend fun types(db: AnkiDatabase): Map<Long, AnkiType> {
                val fields = db.all("SELECT ntid, name FROM fields ORDER BY ntid, ord") {
                    it.getLong(0)!! to it.getString(1)!!
                }.groupBy({ it.first }, { it.second })
                val cards = db.all("SELECT ntid, name, config FROM templates ORDER BY ntid, ord") { r ->
                    val config = proto<CardConfig>(r.getBytes(2))
                    r.getLong(0)!! to AnkiCardType(r.getString(1)!!, config.front, config.back)
                }.groupBy({ it.first }, { it.second })
                return db.all("SELECT id, name, config FROM notetypes") { r ->
                    val id = r.getLong(0)!!
                    val config = proto<TypeConfig>(r.getBytes(2))
                    id to
                        AnkiType(
                            r.getString(1)!!,
                            config.kind == 1,
                            fields[id].orEmpty(),
                            cards[id].orEmpty(),
                            config.css,
                        )
                }.toMap()
            }

            override suspend fun decks(db: AnkiDatabase): Map<Long, AnkiDeck> {
                val kinds = db.all("SELECT name FROM pragma_table_info('decks')") { it.getString(0) }.contains("kind")
                return db.all("SELECT id, name${if (kinds) ", kind" else ""} FROM decks") { r ->
                    val kind = if (kinds) r.getBytes(2) else null
                    val description = kind?.let { runCatching { proto<DeckKind>(it).normal?.description }.getOrNull() }
                    val id = r.getLong(0)!!
                    id to AnkiDeck(id, r.getString(1)!!.split('\u001f'), description.orEmpty())
                }.toMap()
            }
        }
    }
}
