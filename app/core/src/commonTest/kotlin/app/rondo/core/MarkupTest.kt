package app.rondo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkupTest {
    private fun roundTrip(src: String) = Markup.write(Markup.parse(src))

    @Test
    fun formattingSurvivesAWriteAndAReadBack() {
        for (src in listOf(
            "**bold** *italic* __under__ ==mark== `code` H~2~O x^2^",
            "***both*** and **bold *nested* bold**",
            "- one\n- **two**",
            "1. first\n2. second",
            "| a | **b** |\n|---|---|\n| 1 | \$x\$ |",
            "$$\n\\frac{1}{2}\n$$",
            "{{c1::Paris::capital}} is in {{c2::France}}",
            "{漢字|かんじ} reading",
            "line one\nline two\n\nsecond paragraph",
        )) {
            val once = roundTrip(src)
            assertEquals(once, roundTrip(once), src)
            assertEquals(Markup.plain(src), Markup.plain(once), src)
        }
    }

    @Test
    fun dollarRule() {
        fun math(src: String) = Markup.inline(src).filter { it.kind == Run.MATH }.map { it.text }
        assertEquals(listOf("x^2"), math("\$x^2\$"))
        assertEquals(emptyList(), math("It costs \$5 and \$10"))
        assertEquals(emptyList(), math("\$5,\$10"))
        assertEquals(listOf("HOME/"), math("\$HOME/\$USER"))
        assertEquals(emptyList(), math("\\\$HOME/\\\$USER"))
    }

    @Test
    fun specialCharactersAreEscapedOnWrite() {
        val text = "a*b_c=d~e^f`g\$h{i}j|k\\l"
        val runs = Markup.inline(Markup.writeRuns(listOf(Run(text))))
        assertEquals(listOf(text), runs.map { it.text })
        assertEquals(
            "\\- not a list",
            Markup.write(listOf(TextBlock(TextBlock.PARAGRAPH, arrayOf(Run("- not a list"))))),
        )
        assertEquals(TextBlock.PARAGRAPH, Markup.parse("\\- not a list").single().kind)
    }

    @Test
    fun clozesCarryTheirNumberHintAndFormatting() {
        val runs = Markup.inline("x {{c3::**bold** answer::a hint}} y")
        val cloze = runs.filter { it.cloze == 3 }
        assertEquals(listOf("bold", " answer"), cloze.map { it.text })
        assertEquals(Run.BOLD, cloze[0].marks)
        assertTrue(cloze.all { it.hint == "a hint" })
        assertEquals(setOf(1, 2), Markup.clozes("{{c2::b}} {{c1::a}} {{c2::c}}"))
    }

    @Test
    fun tablesKeepHeaderAndCells() {
        val table = Markup.parse("| h1 | h2 |\n|---|---|\n| a \\| b | c |").single()
        assertTrue(table.header)
        assertEquals(listOf("a | b", "c"), table.rows[1].map { Markup.plain(it) })
    }

    @Test
    fun editorDocumentsConvertBothWays() {
        for (src in listOf(
            "**b** {{c1::x::h}} \$y\$ {漢|かん}",
            "- a\n- b",
            "| a | b |\n|---|---|\n| c | d |",
            "$$\nx\n$$",
        )) {
            assertEquals(roundTrip(src), Editor.toMarkup(Editor.toDocument(src)), src)
        }
    }

    @Test
    fun searchFoldsCaseAndAccents() {
        assertEquals("creme brulee", fold("Crème Brûlée"))
        assertEquals("ab cd", searchText(mapOf("1" to "**Ab**", "2" to "{{c1::cd}}"), setOf("1", "2")))
    }
}
