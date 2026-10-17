package app.rondo.core.markup.infrastructure

import app.rondo.core.markup.domain.Markup
import kotlin.test.Test
import kotlin.test.assertEquals

/** Markup as others write it: the editor's documents, and the HTML Anki keeps in a field. */
class FormatsTest {
    private fun roundTrip(src: String) = Markup(Markup(src).blocks).text

    private fun html(src: String) = FieldHtml(Markup(src)).html

    @Test
    fun editorDocumentsConvertBothWays() {
        for (src in listOf(
            "**b** {{c1::x::h}} \$y\$ {漢|かん}",
            "- a\n- b",
            "| a | b |\n|---|---|\n| c | d |",
            "$$\nx\n$$",
        )) {
            assertEquals(roundTrip(src), EditorDocument(EditorDocument(Markup(src)).json).markup, src)
        }
    }

    @Test
    fun htmlIsWhatAnkiKeepsInAField() {
        assertEquals("<b>bold</b> &amp; <i>it&lt;</i><br>line", html("**bold** & *it<*\nline"))
        assertEquals("{{c1::<b>Paris</b>::city}} is \\(x^2\\)", html("{{c1::**Paris**::city}} is \$x^2\$"))
        assertEquals("<ruby>漢<rt>かん</rt></ruby>", html("{漢|かん}"))
        assertEquals("one<br><br><ul><li>a</li><li>b</li></ul>", html("one\n\n- a\n- b"))
        assertEquals("<table><tr><th>a</th></tr><tr><td>b</td></tr></table>", html("| a |\n|---|\n| b |"))
    }
}
