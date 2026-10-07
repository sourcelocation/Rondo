package app.rondo.client

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.rondo.core.Learning
import app.rondo.core.Markup
import app.rondo.core.Memory
import app.rondo.core.Occlusion
import app.rondo.core.Templates
import app.rondo.core.model.FieldDef
import app.rondo.core.model.Note
import com.squareup.zstd.okio.zstdCompress
import java.io.ByteArrayOutputStream
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.buffer

/** Real packages in both of Anki's formats, built here with the tables Anki writes. */
class AnkiImportTest {
    private val crt = 1_700_000_000L
    private val png = byteArrayOf(-119, 80, 78, 71, 1, 2, 3)
    private val mp3 = byteArrayOf(73, 68, 51, 4, 5, 6)

    private fun hash(b: ByteArray) = b.toByteString().sha256().hex()

    private fun rondo() = runBlocking {
        Rondo.open(JvmPlatform("http://localhost:9", "", ":memory:", null), autoSync = false)
    }

    private suspend fun Rondo.notes(): List<Note> = store.q.allNotes().awaitAsList().map { it.model() }

    private fun Connection.exec(sql: String, vararg args: Any?) = prepareStatement(sql).use { s ->
        args.forEachIndexed { i, a -> s.setObject(i + 1, a) }
        s.execute()
    }

    private fun collection(build: Connection.() -> Unit): ByteArray {
        val file = File.createTempFile("anki", ".db").apply { delete() }
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
            c.exec("CREATE TABLE notes (id INTEGER PRIMARY KEY, mid INTEGER, tags TEXT, flds TEXT)")
            c.exec(
                "CREATE TABLE cards (id INTEGER PRIMARY KEY, nid INTEGER, did INTEGER, odid INTEGER, ord INTEGER, " +
                    "type INTEGER, queue INTEGER, due INTEGER, odue INTEGER, ivl INTEGER, factor INTEGER, " +
                    "reps INTEGER, lapses INTEGER, flags INTEGER, data TEXT, mod INTEGER)",
            )
            c.exec("CREATE TABLE revlog (id INTEGER PRIMARY KEY, cid INTEGER, ease INTEGER, time INTEGER)")
            c.build()
        }
        return file.readBytes().also { file.delete() }
    }

    private fun Connection.card(
        id: Long,
        note: Long,
        deck: Long,
        ord: Int,
        type: Int = 0,
        queue: Int = 0,
        due: Long = 0,
        ivl: Int = 0,
        flags: Int = 0,
    ) = exec(
        "INSERT INTO cards VALUES (?, ?, ?, 0, ?, ?, ?, ?, 0, ?, 2500, 3, 1, ?, '', ?)",
        id, note, deck, ord, type, queue, due, ivl, flags, crt,
    )

    private fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            files.forEach { (name, bytes) ->
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
            }
        }
    }.toByteArray()

    private fun zstd(b: ByteArray): ByteArray = Buffer().also { buffer ->
        buffer.zstdCompress().buffer().use { it.write(b) }
    }.readByteArray()

    /** Protobuf by hand: numbers as varints, strings and bytes length-delimited. */
    private fun proto(vararg fields: Pair<Int, Any>): ByteArray = ByteArrayOutputStream().also { out ->
        fun varint(v: Int) {
            var x = v
            while (x >= 0x80) out.write(x and 0x7f or 0x80).also { x = x ushr 7 }
            out.write(x)
        }
        for ((n, v) in fields) {
            if (v is Int) {
                varint(n shl 3)
                varint(v)
            } else {
                val b = if (v is String) v.encodeToByteArray() else v as ByteArray
                varint(n shl 3 or 2)
                varint(b.size)
                out.write(b)
            }
        }
    }.toByteArray()

    private fun legacy(): ByteArray = zip(
        mapOf(
            "collection.anki2" to collection {
                exec("CREATE TABLE col (crt INTEGER, models TEXT, decks TEXT)")
                val models = """{
                  "1": {"name": "Basic", "type": 0, "css": "",
                        "flds": [{"name": "Front", "ord": 0}, {"name": "Back", "ord": 1}],
                        "tmpls": [{"name": "Card 1", "ord": 0, "qfmt": "{{Front}}",
                                   "afmt": "{{FrontSide}}<hr id=answer>{{Back}}"}]},
                  "2": {"name": "Cloze", "type": 1, "css": "",
                        "flds": [{"name": "Text", "ord": 0}, {"name": "Back Extra", "ord": 1}],
                        "tmpls": [{"name": "Cloze", "ord": 0, "qfmt": "{{cloze:Text}}",
                                   "afmt": "{{cloze:Text}}<br>{{Back Extra}}"}]},
                  "3": {"name": "Japanese", "type": 0, "css": "",
                        "flds": [{"name": "Word", "ord": 0}, {"name": "Reading", "ord": 1},
                                 {"name": "Picture", "ord": 2}, {"name": "Sound", "ord": 3}],
                        "tmpls": [{"name": "Read", "ord": 0, "qfmt": "{{Word}}",
                                   "afmt": "{{FrontSide}}<hr id=answer>{{furigana:Reading}}{{Picture}}{{Sound}}"}]}
                }"""
                val decks = """{"1": {"name": "Default"}, "2": {"name": "Languages::Japanese"},
                    "3": {"name": "Math"}}"""
                exec("INSERT INTO col VALUES (?, ?, ?)", crt, models, decks)
                exec(
                    "INSERT INTO notes VALUES (100, 1, ' geo ', ?)",
                    "<b>Capital</b> of <i>France</i><br>today\u001f\\(x^2\\) &nbsp;Paris",
                )
                exec(
                    "INSERT INTO notes VALUES (200, 2, '', ?)",
                    "{{c1::Paris}} is in {{c2::<b>France</b>::country}}\u001fExtra",
                )
                exec(
                    "INSERT INTO notes VALUES (300, 3, '', ?)",
                    "日本\u001f日本[にほん]\u001f<img src=\"flag.png\">\u001f[sound:nihon.mp3]",
                )
                card(1000, 100, 3, 0, type = 2, queue = -1, due = 10, ivl = 5, flags = 1)
                card(2000, 200, 2, 0, due = 2)
                card(2001, 200, 2, 1, due = 3)
                card(3000, 300, 2, 0, due = 1)
                for ((i, ease) in listOf(3, 1, 3).withIndex()) {
                    exec(
                        "INSERT INTO revlog VALUES (?, 1000, ?, 8000)",
                        (crt + i * 86_400) * 1000,
                        ease,
                    )
                }
            },
            "media" to """{"0": "flag.png", "1": "nihon.mp3"}""".encodeToByteArray(),
            "0" to png,
            "1" to mp3,
        ),
    )

    @Test
    fun translatesTheLegacyFormatWithItsHistory() = runBlocking {
        val r = rondo()
        val imported = r.anki.run(legacy(), translate = true, history = true)
        assertEquals(3, imported.notes)
        assertEquals(2, imported.media)
        val tree = r.store.tree()
        assertEquals(listOf(listOf("Languages"), listOf("Math")), tree.roots.map { tree.path(it.id) })
        assertEquals(listOf("Japanese"), tree.children(tree.roots.first().id).map { it.name })

        val notes = r.notes().associateBy { it.templateId }
        val basic = notes.getValue(Templates.BASIC)
        assertEquals(mapOf("1" to "**Capital** of *France*\ntoday", "2" to "\$x^2\$ Paris"), basic.fields)
        val cloze = notes.getValue(Templates.CLOZE)
        assertEquals("{{c1::Paris}} is in {{c2::**France**::country}}", cloze.fields["1"])
        assertEquals(setOf(1, 2), Markup.clozes(cloze.fields.getValue("1")))

        val japanese = r.notes().single { !Templates.isBuiltin(it.templateId) }
        val template = r.store.template(japanese.templateId)!!
        val kinds = listOf(FieldDef.Kind.TEXT, FieldDef.Kind.TEXT, FieldDef.Kind.IMAGE, FieldDef.Kind.AUDIO)
        assertEquals(kinds, template.def.fields.map { it.kind })
        assertEquals(
            mapOf("1" to "日本", "2" to "{日本|にほん}", "3" to hash(png), "4" to hash(mp3)),
            japanese.fields,
        )
        // New cards keep Anki's order: the Japanese note's card came first.
        assertTrue(japanese.id < cloze.id)

        val state = r.store.q.cardsOf(basic.id).awaitAsList().single().model()
        assertEquals(Memory.REVIEW, state.memory.state)
        assertEquals((crt + 10 * 86_400) * 1000, state.memory.due)
        assertTrue(state.suspended)
        assertEquals(1, state.flag)
        assertEquals(crt * 1000, state.introducedAt)
        assertEquals(3, r.store.q.eventsOf(basic.id).awaitAsList().count { it.kind == Learning.REVIEW.toLong() })
    }

    @Test
    fun keepsAnkiHtmlOneToOneAndConvertsAsACopy() = runBlocking {
        val r = rondo()
        r.anki.run(legacy(), translate = false, history = false)
        val notes = r.notes()
        assertTrue(notes.all { r.store.template(it.templateId)!!.kind == Templates.ANKI })
        val japanese = notes.single { it.fields["1"] == "日本" }
        assertEquals("<img src=\"rondo-media:${hash(png)}\">", japanese.fields["3"])
        assertEquals("[sound:rondo-media:${hash(mp3)}]", japanese.fields["4"])
        val basic = notes.single { it.fields["1"].orEmpty().startsWith("<b>") }
        assertTrue(r.store.q.eventsOf(basic.id).awaitAsList().none { it.kind == Learning.REVIEW.toLong() })

        val copy = r.library.copy(basic.deckId, convert = true)
        val converted = r.notes().single { it.deckId == copy }
        assertEquals(Templates.BASIC, converted.templateId)
        assertEquals("**Capital** of *France*\ntoday", converted.fields["1"])
        val state = r.store.q.cardsOf(converted.id).awaitAsList().single().model()
        assertEquals((crt + 10 * 86_400) * 1000, state.memory.due)
    }

    @Test
    fun readsTheLatestFormat() = runBlocking {
        val front = "{{#Header}}<div>{{Header}}</div>{{/Header}}" +
            "<div style=\"display: none\">{{cloze:Occlusion}}</div><div id=\"image-occlusion-container\">{{Image}}</div>"
        val db = collection {
            exec("CREATE TABLE col (crt INTEGER)")
            exec("CREATE TABLE notetypes (id INTEGER, name TEXT, config BLOB)")
            exec("CREATE TABLE fields (ntid INTEGER, ord INTEGER, name TEXT)")
            exec("CREATE TABLE templates (ntid INTEGER, ord INTEGER, name TEXT, config BLOB)")
            exec("CREATE TABLE decks (id INTEGER, name TEXT)")
            exec("INSERT INTO col VALUES (?)", crt)
            exec("INSERT INTO notetypes VALUES (7, 'Image Occlusion', ?)", proto(1 to 1, 3 to ".card {}"))
            listOf("Occlusion", "Image", "Header", "Back Extra", "Comments").forEachIndexed { i, f ->
                exec("INSERT INTO fields VALUES (7, ?, ?)", i, f)
            }
            exec(
                "INSERT INTO templates VALUES (7, 0, 'Image Occlusion', ?)",
                proto(
                    1 to front,
                    2 to "$front<br>{{Back Extra}}",
                ),
            )
            exec("INSERT INTO decks VALUES (5, ?)", "Anatomy\u001fBrain")
            val masks = "{{c1::image-occlusion:rect:left=.1:top=.2:width=.3:height=.1:oi=1}}" +
                "{{c2::image-occlusion:ellipse:left=.5:top=.5:rx=.1:ry=.05:oi=1}}"
            exec(
                "INSERT INTO notes VALUES (1, 7, '', ?)",
                listOf(masks, "<img src=\"brain.png\">", "Brain", "Lobes", "").joinToString("\u001f"),
            )
            card(10, 1, 5, 0)
            card(11, 1, 5, 1)
        }
        val media = proto(1 to proto(1 to "brain.png", 2 to png.size))
        val apkg = zip(
            mapOf(
                "collection.anki2" to "outdated".encodeToByteArray(),
                "collection.anki21b" to zstd(db),
                "media" to zstd(media),
                "0" to zstd(png),
            ),
        )

        val r = rondo()
        r.anki.run(apkg, translate = true, history = true)
        val tree = r.store.tree()
        assertEquals(listOf("Anatomy", "Brain"), tree.path(tree.children(tree.roots.single().id).single().id))
        val note = r.notes().single()
        assertEquals(Templates.OCCLUSION, note.templateId)
        val masks = listOf(Occlusion.Rect(1, 0.1, 0.2, 0.3, 0.1), Occlusion.Rect(2, 0.5, 0.5, 0.2, 0.1))
        assertEquals(mapOf("1" to Occlusion.write(hash(png), masks), "2" to "Brain", "3" to "Lobes"), note.fields)
        assertEquals(listOf(1L, 2L), r.store.q.cardsOf(note.id).awaitAsList().map { it.card })
    }
}
