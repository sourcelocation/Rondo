package app.rondo.client

import app.rondo.client.anki.AnkiConversion
import app.rondo.client.anki.AnkiPackage
import app.rondo.client.anki.Suggestions
import app.rondo.client.services.Imported
import app.rondo.client.services.Plan
import app.rondo.core.markup.domain.Markup
import app.rondo.core.notes.domain.FieldDef
import app.rondo.core.notes.domain.Note
import app.rondo.core.notes.domain.Occlusion
import app.rondo.core.notes.domain.Template
import app.rondo.core.study.domain.Event
import app.rondo.core.study.domain.Memory
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

    private suspend fun Rondo.notes(): List<Note> = store.notes.of(store.notes.ids()).filter { it.deletedAt == null }

    /**
     * What importing [bytes] at the top level would do, as the import screen sets it up by default:
     * made Rondo's ([translate]) with the likely suggestions, with the review [history] or not.
     */
    private suspend fun Rondo.plan(
        bytes: ByteArray,
        translate: Boolean = true,
        history: Boolean = true,
    ): Pair<AnkiPackage, Plan> {
        val pkg = imports.open(bytes) as AnkiPackage
        val conversion = AnkiConversion(pkg.contents())
        val base = if (translate) conversion.contents else pkg.contents()
        val suggested = Suggestions(conversion.contents).all.filter { it.likely }.fold(base) { c, s -> s.apply(c) }
        return pkg to imports.plan(imports.progress(suggested, events = true, answers = history), null)
    }

    private suspend fun Rondo.anki(bytes: ByteArray, translate: Boolean = true, history: Boolean = true): Imported {
        val (pkg, plan) = plan(bytes, translate, history)
        return imports.add(pkg, plan)
    }

    private fun Connection.exec(sql: String, vararg args: Any?) = prepareStatement(sql).use { s ->
        args.forEachIndexed { i, a -> s.setObject(i + 1, a) }
        s.execute()
    }

    private fun collection(build: Connection.() -> Unit): ByteArray {
        val file = File.createTempFile("anki", ".db").apply { delete() }
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
            c.exec("CREATE TABLE notes (id INTEGER PRIMARY KEY, guid TEXT, mid INTEGER, tags TEXT, flds TEXT)")
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
                    "INSERT INTO notes VALUES (100, 'g100', 1, ' geo ', ?)",
                    "<b>Capital</b> of <i>France</i><br>today\u001f\\(x^2\\) &nbsp;Paris",
                )
                exec(
                    "INSERT INTO notes VALUES (200, 'g200', 2, ' marked Geo::Europe leech ', ?)",
                    "{{c1::Paris}} is in {{c2::<b>France</b>::country}}\u001fExtra",
                )
                exec(
                    "INSERT INTO notes VALUES (300, 'g300', 3, '', ?)",
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
        val imported = r.anki(legacy())
        assertEquals(3, imported.notes)
        assertEquals(2, imported.media)
        val tree = r.store.decks.tree()
        assertEquals(listOf(listOf("Languages"), listOf("Math")), tree.roots.map { tree.path(it.id) })
        assertEquals(listOf("Japanese"), tree.children(tree.roots.first().id).map { it.name })

        val notes = r.notes().associateBy { it.templateId }
        val basic = notes.getValue(Template.BASIC.id)
        assertEquals(mapOf("1" to "**Capital** of *France*\ntoday", "2" to "\$x^2\$ Paris"), basic.fields)
        val cloze = notes.getValue(Template.CLOZE.id)
        assertEquals("{{c1::Paris}} is in {{c2::**France**::country}}", cloze.fields["1"])
        assertEquals(setOf(1, 2), Markup(cloze.fields.getValue("1")).clozes)
        // Tags come along; Anki's "marked" becomes a mark on every card, and "leech" goes.
        assertEquals("geo", basic.tags)
        assertEquals("Geo::Europe", cloze.tags)
        assertEquals(listOf(1, 1), r.store.cards.of(cloze.id).map { it.state.flag })

        val japanese = r.notes().single { it.templateId !in Template.builtins }
        val template = r.store.templates.get(japanese.templateId)!!
        val kinds = listOf(FieldDef.Kind.TEXT, FieldDef.Kind.TEXT, FieldDef.Kind.IMAGE, FieldDef.Kind.AUDIO)
        assertEquals(kinds, template.fields.map { it.kind })
        assertEquals(
            mapOf("1" to "日本", "2" to "{日本|にほん}", "3" to hash(png), "4" to hash(mp3)),
            japanese.fields,
        )
        // New cards keep Anki's order: the Japanese note's card came first.
        assertTrue(japanese.id < cloze.id)

        val state = r.store.cards.of(basic.id).single().state
        assertEquals(Memory.REVIEW, state.memory.state)
        assertEquals((crt + 10 * 86_400) * 1000, state.memory.due)
        assertTrue(state.suspended)
        assertEquals(1, state.flag)
        assertEquals(crt * 1000, state.introducedAt)
        assertEquals(3, r.store.events.of(listOf(basic.id)).count { it is Event.Review })
    }

    @Test
    fun keepsAnkiHtmlOneToOneAndConvertsAsACopy() = runBlocking {
        val r = rondo()
        r.anki(legacy(), translate = false, history = false)
        val notes = r.notes()
        assertTrue(notes.all { r.store.templates.get(it.templateId) is app.rondo.core.notes.domain.AnkiTemplate })
        val japanese = notes.single { it.fields["1"] == "日本" }
        assertEquals("<img src=\"rondo-media:${hash(png)}\">", japanese.fields["3"])
        assertEquals("[sound:rondo-media:${hash(mp3)}]", japanese.fields["4"])
        val basic = notes.single { it.fields["1"].orEmpty().startsWith("<b>") }
        assertTrue(r.store.events.of(listOf(basic.id)).none { it is Event.Review })

        val copy = r.library.copy(basic.deckId, convert = true)
        val converted = r.notes().single { it.deckId == copy }
        assertEquals(Template.BASIC.id, converted.templateId)
        assertEquals("**Capital** of *France*\ntoday", converted.fields["1"])
        val state = r.store.cards.of(converted.id).single().state
        assertEquals((crt + 10 * 86_400) * 1000, state.memory.due)
    }

    @Test
    fun importingAgainAddsNothingTwice() = runBlocking {
        for (translate in listOf(true, false)) {
            val r = rondo()
            r.anki(legacy(), translate)
            val decks = r.store.decks.all().size
            val templates = r.store.templates.all().size
            val again = r.anki(legacy(), translate)
            assertEquals(0, again.notes)
            assertEquals(3, again.repeated)
            assertEquals(0, again.media)
            assertEquals(decks, r.store.decks.all().size)
            assertEquals(templates, r.store.templates.all().size)
            assertEquals(3, r.notes().size)
        }
    }

    /** One of a series of shared decks, each a package: a wide note type, Anki's decks named to sort them. */
    private fun level(deck: String, words: List<String>, first: Long): ByteArray = zip(
        mapOf(
            "collection.anki2" to collection {
                exec("CREATE TABLE col (crt INTEGER, models TEXT, decks TEXT)")
                val front = "<div class=hanzi>{{Simplified}}</div>{{Homograph}}<hr><div>{{Sentence}}</div>"
                val back = "$front<div class=pinyin>{{Pinyin}}</div>{{Meaning}}"
                val fields = listOf("Key", "Simplified", "Pinyin", "Meaning", "Homograph", "Sentence")
                    .mapIndexed { i, f -> """{"name": "$f", "ord": $i}""" }.joinToString()
                val models = """{"9": {"name": "HSK", "type": 0, "css": ".card {font-size: 10px} .hanzi {font-size: 78px}",
                    "flds": [$fields], "tmpls": [{"name": "Recognition ", "ord": 0, "qfmt": "$front", "afmt": "$back"}]}}"""
                val decks = """{"1": {"name": "Default"},
                    "7": {"name": "$deck", "desc": "From <b>the</b> shared deck."}}"""
                exec("INSERT INTO col VALUES (?, ?, ?)", crt, models, decks)
                words.forEachIndexed { i, w ->
                    val id = first + i
                    val flds = listOf("${i + 1}", w, "pinyin", "meaning", "", "我有$w。").joinToString("\u001f")
                    exec("INSERT INTO notes VALUES (?, ?, 9, ' HSK ', ?)", id, "hsk-$w", flds)
                    card(id * 10, id, 7, 0, due = i.toLong())
                }
            },
            "media" to "{}".encodeToByteArray(),
        ),
    )

    @Test
    fun aSeriesOfSharedDecksLandsInOneTree() = runBlocking {
        val r = rondo()
        val one = r.anki(level("Mandarin: Vocabulary::b. HSK1", listOf("的", "我", "你"), 100))
        assertEquals(3, one.notes)
        // Every note has its card, though none has a homograph.
        assertEquals(3, r.notes().sumOf { r.store.cards.of(it.id).size })
        val tree = r.store.decks.tree()
        val root = tree.roots.single()
        assertEquals("Mandarin: Vocabulary", root.name)
        val hsk1 = tree.children(root.id).single()
        // Anki's names as they are.
        assertEquals("b. HSK1", hsk1.name)
        assertEquals("zh", tree.language(hsk1.id))
        assertEquals("From **the** shared deck.", hsk1.description)
        // Its notes are titled by their word, not by the number Anki sorted them with.
        val hsk = r.store.templates.get(r.notes().first().templateId)!!
        assertEquals(listOf("的", "我", "你"), r.notes().sortedBy { it.id }.map { hsk.label(it) })

        // The next level joins it: same root, same note type, a new deck after the first.
        val (pkg, plan) = r.plan(level("Mandarin: Vocabulary::c. HSK2", listOf("他", "好"), 200))
        assertEquals(listOf("c. HSK2"), plan.decks.map { it.name })
        assertTrue(plan.templates.isEmpty())
        r.imports.add(pkg, plan)
        val after = r.store.decks.tree()
        assertEquals(listOf("b. HSK1", "c. HSK2"), after.children(after.roots.single().id).map { it.name })
        assertEquals(1, r.notes().map { it.templateId }.distinct().size)
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
                "INSERT INTO notes VALUES (1, 'g1', 7, '', ?)",
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
        r.anki(apkg)
        val tree = r.store.decks.tree()
        assertEquals(listOf("Anatomy", "Brain"), tree.path(tree.children(tree.roots.single().id).single().id))
        val note = r.notes().single()
        assertEquals(Template.OCCLUSION.id, note.templateId)
        val masks = listOf(Occlusion.Rect(1, 0.1, 0.2, 0.3, 0.1), Occlusion.Rect(2, 0.5, 0.5, 0.2, 0.1))
        assertEquals(mapOf("1" to Occlusion(hash(png), masks).text, "2" to "Brain", "3" to "Lobes"), note.fields)
        assertEquals(listOf(1, 2), r.store.cards.of(note.id).map { it.card }.sorted())
    }
}
