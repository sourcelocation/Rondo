package app.rondo.core

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
    fun editorDocumentsConvertBothWays() {
        for (src in listOf(
            "**b** {{c1::x::h}} \$y\$ {漢|かん}",
            "- a\n- b",
            "| a | b |\n|---|---|\n| c | d |",
            "$$\nx\n$$",
        )) {
            assertEquals(roundTrip(src), EditorDocument(Markup(src).document).markup, src)
        }
    }

    @Test
    fun searchFoldsCaseAndAccents() {
        assertEquals("creme brulee", Folded("Crème Brûlée").text)
        val n = note(deck(newId()), fields = mapOf("1" to "**Ab**", "2" to "{{c1::cd}}"))
        assertEquals("ab cd", Template.BASIC.searchText(n))
    }

    @Test
    fun htmlIsWhatAnkiKeepsInAField() {
        assertEquals("<b>bold</b> &amp; <i>it&lt;</i><br>line", Markup("**bold** & *it<*\nline").html)
        assertEquals("{{c1::<b>Paris</b>::city}} is \\(x^2\\)", Markup("{{c1::**Paris**::city}} is \$x^2\$").html)
        assertEquals("<ruby>漢<rt>かん</rt></ruby>", Markup("{漢|かん}").html)
        assertEquals("one<br><br><ul><li>a</li><li>b</li></ul>", Markup("one\n\n- a\n- b").html)
        assertEquals("<table><tr><th>a</th></tr><tr><td>b</td></tr></table>", Markup("| a |\n|---|\n| b |").html)
    }
}
