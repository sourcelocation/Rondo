package app.rondo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplatesTest {
    private val owner = newId()
    private val d = deck(owner)

    @Test
    fun builtinsAreValid() {
        for (t in Template.builtins.values) assertNull(t.with(id = newId()).problem, t.name)
        val basic = Template.BASIC
        assertEquals("empty front", basic.copy(cards = listOf(basic.cards[0].copy(front = emptyList()))).problem)
        assertEquals(
            "block field",
            basic.copy(cards = listOf(basic.cards[0].copy(back = listOf(Block.Field(9))))).problem,
        )
    }

    @Test
    fun cardsFollowTheFieldsAndClozes() {
        val reverse = Template.REVERSE
        assertEquals(listOf(1, 2), reverse.cards(note(d)))
        assertEquals(listOf(1), reverse.cards(note(d, fields = mapOf("1" to "front"))))
        val cloze = Template.CLOZE
        assertEquals(listOf(1, 3), cloze.cards(note(d, cloze.id, mapOf("1" to "{{c3::a}} {{c1::b}}"))))
        val hash = "a".repeat(64)
        val image = Occlusion(
            hash,
            listOf(Occlusion.Rect(2, 0.1, 0.1, 0.2, 0.2), Occlusion.Rect(1, 0.5, 0.5, 0.1, 0.1)),
        ).text
        val occlusion = Template.OCCLUSION
        assertEquals(listOf(1, 2), occlusion.cards(note(d, occlusion.id, mapOf("1" to image))))
        assertEquals("occlusion", occlusion.problem(mapOf("1" to "not a hash")))
    }

    @Test
    fun clozeLayoutHidesOnlyTheActiveOne() {
        val cloze = Template.CLOZE
        val n = note(d, cloze.id, mapOf("1" to "{{c1::Paris::city}} is in {{c2::France}}"))
        fun runs(back: Boolean) = cloze.side(n, 1, back).pieces.first().blocks.first().runs.toList()
        val front = runs(false)
        assertEquals(listOf(Run.BLANK, Run.TEXT, Run.TEXT), front.map { it.kind })
        assertEquals("city", front[0].text)
        assertEquals("France", front[2].text)
        val back = runs(true)
        assertTrue(back[0].marks and Run.REVEALED != 0)
        assertEquals(0, back[2].marks and Run.REVEALED)
    }

    @Test
    fun layoutCarriesTypeInAnswersAndSpeech() {
        val type = Template.TYPE
        val typed = type.side(note(d, type.id, mapOf("1" to "Q", "2" to "**Answer**")), 1, false)
        assertEquals("Answer", typed.pieces.first { it.kind == "typein" }.text)
        val vocabulary = Template.VOCABULARY
        val word = vocabulary.side(note(d, vocabulary.id, mapOf("1" to "猫", "3" to "cat")), 1, false)
        assertEquals("猫", word.pieces.single().speech)
    }

    @Test
    fun ankiSidesRenderSectionsClozesAndFurigana() {
        val fields = mapOf(
            "Front" to "<b>Q</b>",
            "Back" to "",
            "Reading" to "漢字[かんじ]",
            "Text" to "{{c1::a::h}} {{c2::b}}",
        )
        fun render(source: String, card: Int = 0, back: Boolean = false, front: String = "") =
            AnkiSide(source).render(fields, card, back, front)
        assertEquals("Q! ", render("{{text:Front}}!{{#Back}}has back{{/Back}} {{^Back}}{{/Back}}"))
        assertEquals("<ruby>漢字<rt>かんじ</rt></ruby>", render("{{furigana:Reading}}"))
        assertEquals("<span class=\"cloze\">[h]</span> b", render("{{cloze:Text}}", 1))
        assertEquals("F|back", render("{{FrontSide}}|back", 1, true, "F"))
    }

    @Test
    fun repeatsAreFoundByTheFirstFieldAsPlainTextCaseAside() {
        val basic = Template.BASIC
        fun key(front: String) = basic.key(mapOf("1" to front, "2" to "anything"))
        assertEquals("hablar", key(" **Hablar**  "))
        assertEquals(key("差不多"), key("差不多"))
        assertEquals(key("chàbuduō"), key("chàbuduō"))
        // Accents tell words apart: a potato isn't a dad.
        assertNotEquals(key("papa"), key("papá"))
        assertNull(key(" "))
        assertNull(basic.key(mapOf("2" to "only a back")))
    }

    @Test
    fun notesAreComparedUnderTheirTopLevelDeck() {
        val root = Deck("Chinese", "a", 1, "me", null)
        val hsk = Deck("HSK", "a", 1, "me", root.id)
        val hsk3 = Deck("HSK3", "a", 1, "me", hsk.id)
        val other = Deck("Spanish", "b", 1, "me", null)
        val filter = Template.BASIC.repeats(Tree(listOf(root, hsk, hsk3, other)), hsk3.id, "差不多")
        assertEquals(setOf(root.id, hsk.id, hsk3.id), filter.decks.toSet())
        assertEquals(listOf(Template.BASIC.id), filter.templates)
        assertEquals("差不多", filter.text)
    }
}
