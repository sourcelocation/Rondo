package app.rondo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplatesTest {
    private val owner = Ids.new()
    private val d = deck(owner)
    private fun t(id: String) = Templates.builtin(id)!!

    @Test
    fun builtinsAreValid() {
        for (t in Templates.builtins) assertNull(Templates.problem(t.copy(id = Ids.new())), t.name)
        val basic = t(Templates.BASIC)
        assertEquals(
            "block",
            Templates.problem(
                basic.copy(
                    def = basic.def.copy(
                        cards = listOf(basic.def.cards[0].copy(front = listOf(app.rondo.core.model.Block()))),
                    ),
                ),
            ),
        )
    }

    @Test
    fun cardsFollowTheFieldsAndClozes() {
        assertEquals(listOf(1, 2), Templates.cards(t(Templates.REVERSE), note(d)))
        assertEquals(listOf(1), Templates.cards(t(Templates.REVERSE), note(d, fields = mapOf("1" to "front"))))
        assertEquals(
            listOf(1, 3),
            Templates.cards(t(Templates.CLOZE), note(d, Templates.CLOZE, mapOf("1" to "{{c3::a}} {{c1::b}}"))),
        )
        val hash = "a".repeat(64)
        val image = Occlusion.write(
            hash,
            listOf(Occlusion.Rect(2, 0.1, 0.1, 0.2, 0.2), Occlusion.Rect(1, 0.5, 0.5, 0.1, 0.1)),
        )
        assertEquals(
            listOf(1, 2),
            Templates.cards(t(Templates.OCCLUSION), note(d, Templates.OCCLUSION, mapOf("1" to image))),
        )
        assertEquals("occlusion", Templates.fieldsProblem(t(Templates.OCCLUSION), mapOf("1" to "not a hash")))
    }

    @Test
    fun clozeLayoutHidesOnlyTheActiveOne() {
        val n = note(d, Templates.CLOZE, mapOf("1" to "{{c1::Paris::city}} is in {{c2::France}}"))
        fun runs(back: Boolean) =
            Templates.layout(t(Templates.CLOZE), n, 1, back).pieces.first().blocks.first().runs.toList()
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
        val typed = Templates.layout(
            t(Templates.TYPE),
            note(d, Templates.TYPE, mapOf("1" to "Q", "2" to "**Answer**")),
            1,
            false,
        )
        assertEquals("Answer", typed.pieces.first { it.kind == "typein" }.text)
        val vocab = Templates.layout(
            t(Templates.VOCABULARY),
            note(
                d,
                Templates.VOCABULARY,
                mapOf(
                    "1" to "猫",
                    "3" to "cat",
                ),
            ),
            1,
            false,
        )
        assertEquals("猫", vocab.pieces.single().speech)
    }

    @Test
    fun ankiTemplatesRenderSectionsClozesAndFurigana() {
        val fields = mapOf(
            "Front" to "<b>Q</b>",
            "Back" to "",
            "Reading" to "漢字[かんじ]",
            "Text" to "{{c1::a::h}} {{c2::b}}",
        )
        assertEquals(
            "Q! ",
            Anki.render("{{text:Front}}!{{#Back}}has back{{/Back}} {{^Back}}{{/Back}}", fields, 0, false, ""),
        )
        assertEquals("<ruby>漢字<rt>かんじ</rt></ruby>", Anki.render("{{furigana:Reading}}", fields, 0, false, ""))
        assertEquals("<span class=\"cloze\">[h]</span> b", Anki.render("{{cloze:Text}}", fields, 1, false, ""))
        assertEquals("F|back", Anki.render("{{FrontSide}}|back", fields, 1, true, "F"))
    }
}
