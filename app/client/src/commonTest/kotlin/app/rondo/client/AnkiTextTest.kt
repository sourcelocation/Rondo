package app.rondo.client

import app.rondo.core.Ids
import app.rondo.core.Templates
import app.rondo.core.model.CardDef
import app.rondo.core.model.FieldDef
import app.rondo.core.model.Note
import app.rondo.core.model.Rows
import app.rondo.core.model.Template
import app.rondo.core.model.TemplateDef
import kotlin.test.Test
import kotlin.test.assertEquals

/** Anki's HTML as Rondo markup, on every platform (JavaScript's regular expressions are stricter). */
class AnkiTextTest {
    private fun translate(front: String, back: String, vararg fields: String): Note {
        val names = fields.indices.map { FieldDef(it + 1, "F${it + 1}", FieldDef.Kind.TEXT) }
        val card = CardDef(1, "Card", emptyList(), emptyList(), ankiFront = front, ankiBack = back)
        val t = Template(Ids.new(), "Anki", Templates.ANKI, TemplateDef(names, listOf(card)), 0)
        val values = fields.withIndex().associate { (i, value) -> "${i + 1}" to value }
        val note = Note(Ids.new(), Ids.new(), t.id, values, 0)
        return Rows(templates = listOf(t), notes = listOf(note)).let(AnkiText::rows).notes!!.single()
    }

    @Test
    fun turnsFormattingMathAndClozesIntoMarkup() {
        val n = translate(
            "{{cloze:F1}}",
            "{{cloze:F1}}<br>{{F2}}",
            "<b>{{c1::Paris}}</b> is \\(x^2\\) in {{c2::France::country}}<div>[$]y[/$]</div>",
            "Extra",
        )
        assertEquals(Templates.CLOZE, n.templateId)
        assertEquals("{{c1::**Paris**}} is \$x^2\$ in {{c2::France::country}}\n\$y\$", n.fields["1"])
    }

    @Test
    fun readsFuriganaWhereTheTemplateUsesIt() {
        val n = translate("{{F1}}", "{{FrontSide}}<hr id=answer>{{furigana:F2}}", "日本", "日本[にほん]語[ご]")
        assertEquals("{日本|にほん}{語|ご}", n.fields["2"])
    }
}
