package app.rondo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Anki's template language as Anki reads it, and Rondo's cards keeping Anki's rule for when they exist. */
class AnkiTest {
    private val owner = newId()
    private val d = deck(owner)
    private val fields = setOf("Word", "Hint", "Sentence", "Back", "Add Reverse", "Picture")

    @Test
    fun aCardExistsWhenAFieldItsFrontShowsIsFilled() {
        // The front shows three fields: any one filled makes the card (static text and Anki's own fields don't).
        val front = AnkiSide("<div class=big>{{Word}}</div>{{Hint}}<hr>Say: {{Sentence}} {{Tags}}")
        assertEquals(listOf(setOf("Word"), setOf("Hint"), setOf("Sentence")), front.requirements(fields))
        // A section asks for its field too; one inverted doesn't.
        val optional = AnkiSide("{{#Add Reverse}}{{Back}}{{/Add Reverse}}")
        assertEquals(listOf(setOf("Add Reverse", "Back")), optional.requirements(fields))
        val inverted = AnkiSide("{{^Picture}}{{Word}}{{/Picture}}{{#Picture}}{{Picture}}{{Word}}{{/Picture}}")
        assertEquals(listOf(setOf("Word"), setOf("Picture")), inverted.requirements(fields))
        assertTrue(AnkiSide("Just text {{FrontSide}}").requirements(fields).isEmpty())
    }

    @Test
    fun aFieldIsEmptyOnlyWithNothingButSpacesAndBreaks() {
        val side = AnkiSide("{{Word}}")
        assertTrue(!side.shows(mapOf("Word" to " <br> <div></div>\n")))
        assertTrue(side.shows(mapOf("Word" to "<img src=\"a.png\">")))
        assertTrue(side.shows(mapOf("Word" to "&nbsp;")))
    }

    @Test
    fun ankiNoteTypesMakeTheCardsAnkiDoes() {
        val names = listOf("Word", "Homograph", "Sentence", "Picture", "Add Reverse")
        val cards = listOf(
            AnkiCardDef(1, "Read", "{{Word}}{{Homograph}}<hr>{{Sentence}}", ""),
            AnkiCardDef(2, "Picture", "{{Picture}}", ""),
            AnkiCardDef(3, "Reverse", "{{#Add Reverse}}{{Sentence}}{{/Add Reverse}}", ""),
        )
        val defs = names.mapIndexed { i, n -> FieldDef(i + 1, n, FieldDef.Kind.TEXT) }
        val t = AnkiTemplate(newId(), "Anki", defs, cards, "", 1, owner)
        // No homograph, a picture only, and nothing asking for the reverse.
        val n = note(d, t.id, mapOf("1" to "的", "3" to "我的", "4" to "<img src=\"rondo-media:${"a".repeat(64)}\">"))
        assertEquals(listOf(1, 2), t.cards(n))
        assertEquals(listOf(1, 2, 3), t.cards(n.copy(fields = n.fields + ("5" to "y"))))
        assertEquals(setOf("a".repeat(64)), t.media(n))
    }

    @Test
    fun ankiClozeNoteTypesMakeACardPerCloze() {
        val defs = listOf(FieldDef(1, "Text", FieldDef.Kind.TEXT), FieldDef(2, "Extra", FieldDef.Kind.TEXT))
        val t =
            AnkiTemplate(
                newId(),
                "Cloze",
                defs,
                listOf(AnkiCardDef(1, "Cloze", "{{cloze:Text}}", "{{cloze:Text}}<br>{{Extra}}")),
                "",
                1,
                owner,
            )
        assertEquals("Text", t.clozeField)
        val n = note(d, t.id, mapOf("1" to "{{c2::a}} {{c1::b}} {{c2::c}}"))
        assertEquals(listOf(1, 2), t.cards(n))
        assertEquals("<span class=\"cloze\">[…]</span> b <span class=\"cloze\">[…]</span>", t.side(n, 2, false).html)
    }

    @Test
    fun sectionsLeftOpenCloseAndStrayClosingTagsGo() {
        val side = AnkiSide("a{{/Nope}}{{#Word}}b{{Word}}")
        assertEquals("abW", side.render(mapOf("Word" to "W"), 1, false, ""))
        assertEquals("a", side.render(mapOf("Word" to ""), 1, false, ""))
    }

    @Test
    fun rondoCardsCanFollowAnkisRule() {
        val f = listOf(1, 2, 3).map { FieldDef(it, "F$it", FieldDef.Kind.TEXT) }
        val front = listOf(Block.Field(1), Block.Field(2))
        val card = CardDef(1, "Card", front, front + Block.Field(3), requires = listOf(listOf(1), listOf(2)))
        val t = RondoTemplate(newId(), "T", f, listOf(card), 1, owner)
        assertNull(t.problem)
        assertEquals(listOf(1), t.cards(note(d, t.id, mapOf("2" to "x"))))
        assertEquals(emptyList(), t.cards(note(d, t.id, mapOf("3" to "x"))))
        // Without it, every field the front shows is needed.
        val plain = t.copy(cards = listOf(card.copy(requires = null)))
        assertEquals(emptyList(), plain.cards(note(d, t.id, mapOf("2" to "x"))))
        // It names fields the note type has, and isn't for cards made one per cloze.
        assertEquals("requires", t.copy(cards = listOf(card.copy(requires = listOf(listOf(9))))).problem)
        assertEquals("requires", t.copy(cards = listOf(card.copy(each = 1))).problem)
        assertEquals("requires", t.copy(cards = listOf(card.copy(requires = emptyList()))).problem)
    }
}
