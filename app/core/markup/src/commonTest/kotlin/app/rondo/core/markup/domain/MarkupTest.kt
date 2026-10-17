package app.rondo.core.markup.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkupTest {
    private fun roundTrip(src: String) = Markup(Markup(src).blocks).text

    private fun inline(src: String) = Markup(src).blocks.single().runs.toList()

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
            assertEquals(Markup(src).plain, Markup(once).plain, src)
        }
    }

    @Test
    fun dollarRule() {
        fun math(src: String) = inline(src).filter { it.kind == Run.MATH }.map { it.text }
        assertEquals(listOf("x^2"), math("\$x^2\$"))
        assertEquals(emptyList(), math("It costs \$5 and \$10"))
        assertEquals(emptyList(), math("\$5,\$10"))
        assertEquals(listOf("HOME/"), math("\$HOME/\$USER"))
        assertEquals(emptyList(), math("\\\$HOME/\\\$USER"))
    }

    @Test
    fun specialCharactersAreEscapedOnWrite() {
        val text = "a*b_c=d~e^f`g\$h{i}j|k\\l"
        val runs = inline(Markup(listOf(TextBlock(TextBlock.PARAGRAPH, arrayOf(Run(text))))).text)
        assertEquals(listOf(text), runs.map { it.text })
        assertEquals(
            "\\- not a list",
            Markup(listOf(TextBlock(TextBlock.PARAGRAPH, arrayOf(Run("- not a list"))))).text,
        )
        assertEquals(TextBlock.PARAGRAPH, Markup("\\- not a list").blocks.single().kind)
    }

    @Test
    fun clozesCarryTheirNumberHintAndFormatting() {
        val runs = inline("x {{c3::**bold** answer::a hint}} y")
        val cloze = runs.filter { it.cloze == 3 }
        assertEquals(listOf("bold", " answer"), cloze.map { it.text })
        assertEquals(Run.BOLD, cloze[0].marks)
        assertTrue(cloze.all { it.hint == "a hint" })
        assertEquals(setOf(1, 2), Markup("{{c2::b}} {{c1::a}} {{c2::c}}").clozes)
    }

    @Test
    fun tablesKeepHeaderAndCells() {
        val table = Markup("| h1 | h2 |\n|---|---|\n| a \\| b | c |").blocks.single()
        assertTrue(table.header)
        assertEquals(listOf("a | b", "c"), table.rows[1].map { cell -> cell.joinToString("") { it.text } })
    }

    @Test
    fun foldingTakesCaseAndAccentsAway() {
        assertEquals("creme brulee", Folded("Crème Brûlée").text)
        assertEquals(Folded("Papá", accents = true).text, Folded("papa\u0301", accents = true).text)
        assertTrue(Folded("papa", accents = true).text != Folded("papá", accents = true).text)
    }
}
