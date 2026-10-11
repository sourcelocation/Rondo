package app.rondo.client

import app.rondo.client.anki.AnkiConversion
import app.rondo.client.anki.Suggestion
import app.rondo.client.anki.Suggestions
import app.rondo.core.AnkiCardDef
import app.rondo.core.AnkiTemplate
import app.rondo.core.Block
import app.rondo.core.Deck
import app.rondo.core.FieldDef
import app.rondo.core.Note
import app.rondo.core.RondoTemplate
import app.rondo.core.Template
import app.rondo.core.file.Contents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Anki's note types made Rondo's, exactly, and what's suggested on top (on every platform: JavaScript's regular expressions are stricter). */
class AnkiConvertTest {
    private fun id() = Uuid.generateV7().toString()

    private val deck = Deck("Deck", "V", 1, id(), null)

    private fun anki(names: List<String>, cards: List<Pair<String, String>>, css: String = ""): AnkiTemplate {
        val fields = names.mapIndexed { i, n -> FieldDef(i + 1, n, FieldDef.Kind.TEXT) }
        val defs = cards.mapIndexed { i, (front, back) -> AnkiCardDef(i + 1, "Card ${i + 1}", front, back) }
        return AnkiTemplate(id(), "Anki", fields, defs, css)
    }

    private fun note(t: Template, vararg fields: Pair<String, String>): Note {
        val ids = t.fields.associate { it.name to it.id.toString() }
        return Note(id(), deck.id, t.id, fields.associate { (k, v) -> ids.getValue(k) to v }, 0)
    }

    private fun convert(t: Template, vararg notes: Note) =
        AnkiConversion(listOf(Contents(deck, notes.map { Contents.Entry(it, t) })))

    private fun AnkiConversion.entries() = contents.single().notes

    private fun translate(front: String, back: String, vararg fields: String): Note {
        val t = anki(fields.indices.map { "F${it + 1}" }, listOf(front to back))
        val note = Note(id(), deck.id, t.id, fields.withIndex().associate { (i, v) -> "${i + 1}" to v }, 0)
        return convert(t, note).entries().single().note
    }

    private fun hash(c: Char) = c.toString().repeat(64)

    @Test
    fun turnsFormattingMathAndClozesIntoMarkup() {
        val n = translate(
            "{{cloze:F1}}",
            "{{cloze:F1}}<br>{{F2}}",
            "<b>{{c1::Paris}}</b> is \\(x^2\\) in {{c2::France::country}}<div>[$]y[/$]</div>",
            "Extra",
        )
        assertEquals(Template.CLOZE.id, n.templateId)
        assertEquals("{{c1::**Paris**}} is \$x^2\$ in {{c2::France::country}}\n\$y\$", n.fields["1"])
    }

    @Test
    fun readsFuriganaWhereTheTemplateUsesIt() {
        val n = translate("{{F1}}", "{{FrontSide}}<hr id=answer>{{furigana:F2}}", "日本", "日本[にほん]語[ご]")
        assertEquals(Template.BASIC.id, n.templateId)
        assertEquals("{日本|にほん}{語|ご}", n.fields["2"])
    }

    /** A vocabulary note type as shared decks make them: wide, with a field on the front most notes leave empty. */
    @Test
    fun keepsEveryCardAsTheTemplateLaysItOut() {
        val names = listOf(
            "Key", "Simplified", "Pinyin", "Meaning", "Part of speech", "Audio", "Homograph", "Sentence",
            "Sentence pinyin", "Sentence meaning", "Sentence audio", "Sentence image", "Traditional",
        )
        val word = "<div class=hanzi>{{Simplified}}</div><span style=\"color: #B80000; font-size: 22px\">{{Homograph}}</span>"
        val front = "$word<div class=pinyin><br></div><hr><div class=sentence>{{Sentence}}</div>"
        val back = "$word<div class=pinyin>{{Pinyin}}</div><div class=english>{{Meaning}}</div>" +
            "<div class=description>{{Part of speech}}</div><hr><div class=sentence>{{Sentence}}</div>" +
            "<div class=pinyinSen>{{Sentence pinyin}}</div><div class=meaningSent>{{Sentence meaning}}</div>" +
            "{{Audio}} {{Sentence audio}}<br>{{Sentence image}}"
        val t = anki(names, listOf(front to back), ".card {font-size: 10px} .hanzi {font-size: 78px;}")
        fun word(key: String, hanzi: String, pinyin: String, meaning: String, homograph: String = "") = note(
            t, "Key" to key, "Simplified" to hanzi, "Pinyin" to pinyin, "Meaning" to meaning, "Part of speech" to "pronoun",
            "Audio" to "[sound:rondo-media:${hash('a')}]", "Homograph" to homograph,
            "Sentence" to "<div><b>$hanzi</b>是学生。</div>", "Sentence pinyin" to "<div>$pinyin shì xuéshēng.</div>",
            "Sentence meaning" to "I'm a student.", "Sentence audio" to "<div>[sound:rondo-media:${hash('b')}]</div>",
            "Sentence image" to "<img src=\"rondo-media:${hash('c')}\" /><br>", "Traditional" to hanzi,
        )
        val result = convert(t, word("1", "的", "de", "of", homograph = "得"), word("2", "我", "wǒ", "I; me"), word("3", "你", "nǐ", "you"))
        assertTrue(result.kept.isEmpty())
        val made = result.entries().first().template as RondoTemplate

        // Anki's fields as they are: their names, their order, their ids. Sounds and pictures are fields of their kind.
        assertEquals(names, made.fields.map { it.name })
        assertEquals((1..names.size).toList(), made.fields.map { it.id })
        val kinds = made.fields.associate { it.name to it.kind }
        assertEquals(FieldDef.Kind.AUDIO, kinds["Audio"])
        assertEquals(FieldDef.Kind.IMAGE, kinds["Sentence image"])

        // Every note has its card, though only one has a homograph: Anki's rule comes along.
        for (e in result.entries()) assertEquals(listOf(1), made.cards(e.note))
        val card = made.cards.single()
        assertEquals(listOf(listOf(2), listOf(7), listOf(8)), card.requires)

        // Each side shows what its template does, in its order; rules divide. Nothing is sized.
        fun shown(blocks: List<Block>) = blocks.map { b -> if (b is Block.Field) made.fields.first { it.id == b.id }.name else "—" }
        assertEquals(listOf("Simplified", "Homograph", "—", "Sentence"), shown(card.front))
        assertEquals(
            listOf(
                "Simplified", "Homograph", "Pinyin", "Meaning", "Part of speech", "—", "Sentence", "Sentence pinyin",
                "Sentence meaning", "Audio", "Sentence audio", "Sentence image",
            ),
            shown(card.back),
        )
        assertTrue((card.front + card.back).none { it is Block.Field && it.size != null })

        // What's suggested on top, all likely: the word as the title, large, and the deck in Chinese.
        val suggestions = Suggestions(result.contents).all
        assertTrue(suggestions.all { it.likely })
        assertEquals(setOf("title", "large", "language"), suggestions.map { it::class.simpleName!!.lowercase() }.toSet())
        val applied = suggestions.fold(result.contents) { c, s -> s.apply(c) }
        val better = applied.single().notes.first().template as RondoTemplate
        assertEquals("Simplified", better.fields.first().name)
        assertEquals(Block.Size.LARGE, (better.cards.single().front.first() as Block.Field).size)
        assertEquals("zh", applied.single().deck.language)
        assertEquals(listOf("Simplified", "Homograph", "Sentence", "Traditional"), better.fields.filter { it.tts }.map { it.name })
        // The title names the notes now.
        assertEquals("的", better.label(applied.single().notes.first().note))
    }

    @Test
    fun anOptionalCardKeepsItsCondition() {
        val t = anki(
            listOf("Front", "Back", "Add Reverse"),
            listOf(
                "{{Front}}" to "{{FrontSide}}<hr id=answer>{{Back}}",
                "{{#Add Reverse}}{{Back}}{{/Add Reverse}}" to "{{FrontSide}}<hr id=answer>{{Front}}",
            ),
        )
        val one = note(t, "Front" to "hola", "Back" to "hello")
        val both = note(t, "Front" to "adiós", "Back" to "goodbye", "Add Reverse" to "y")
        val result = convert(t, one, both)
        val made = result.entries().first().template as RondoTemplate
        val notes = result.entries().map { it.note }.associateBy { it.id }
        assertEquals(listOf(1), made.cards(notes.getValue(one.id)))
        assertEquals(listOf(1, 2), made.cards(notes.getValue(both.id)))
        assertEquals(null, made.cards[0].requires)
        assertEquals(listOf(listOf(2, 3)), made.cards[1].requires)
    }

    @Test
    fun aNoteTypeRondoCantFollowKeepsAnkisLook() {
        // The picture's name goes into the image's address: Rondo's cards have nowhere to put that.
        val t = anki(listOf("Word", "Picture"), listOf("{{Word}}" to "{{FrontSide}}<img src=\"{{Picture}}\">"))
        val n = note(t, "Word" to "cat", "Picture" to "cat.jpg")
        val result = convert(t, n)
        assertEquals(mapOf(t.id to AnkiConversion.Kept.LAYOUT), result.kept)
        assertEquals(t, result.entries().single().template)
        assertEquals(n, result.entries().single().note)
    }

    @Test
    fun picturesBeyondTheFirstGetFieldsOfTheirOwn() {
        val t = anki(listOf("Front", "Back"), listOf("{{Front}}" to "{{FrontSide}}<hr id=answer>{{Back}}"))
        val two = "Look:<img src=\"rondo-media:${hash('a')}\"><img src=\"rondo-media:${hash('b')}\">"
        val made = convert(t, note(t, "Front" to "Q", "Back" to two)).entries().single()
        assertEquals(listOf("Front", "Back", "Back image", "Back image 2"), made.template.fields.map { it.name })
        assertEquals(mapOf("1" to "Q", "2" to "Look:", "3" to hash('a'), "4" to hash('b')), made.note.fields)
    }

    @Test
    fun languagesAreToldByTheirScript() {
        fun language(vararg titles: String): String? {
            val t = anki(listOf("Word"), listOf("{{Word}}" to "{{FrontSide}}"))
            val converted = convert(t, *titles.map { note(t, "Word" to it) }.toTypedArray()).contents
            return Suggestions(converted).all.filterIsInstance<Suggestion.Language>().singleOrNull()?.language
        }
        assertEquals("ja", language("日本", "猫", "にほん"))
        assertEquals("zh", language("的", "我"))
        assertEquals("ko", language("안녕"))
        assertEquals("uk", language("їжак"))
        assertEquals(null, language("hola", "adiós"))
    }
}
