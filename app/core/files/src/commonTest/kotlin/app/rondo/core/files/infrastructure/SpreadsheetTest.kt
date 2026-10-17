package app.rondo.core.files.infrastructure

import app.rondo.core.files.application.Made
import app.rondo.core.files.application.Rows
import app.rondo.core.notes.domain.Note
import app.rondo.core.notes.domain.Template
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CSV and TSV files as people and other apps write them, read into rows. */
class SpreadsheetTest {
    private val basic = Template.BASIC
    private val vocabulary = Template.VOCABULARY
    private val builtins = Template.builtins.values.toList()
    private var clock = 0L

    private fun notes(table: Spreadsheet, existing: Set<String> = emptySet()): Made {
        val t = table.rows.template(builtins)
        return table.rows.notes(table.rows.body, table.rows.columns(t), t, "deck", "me", {
            ++clock
        }, existing, now = 1_000_000)
    }

    @Test
    fun aSpreadsheetsTwoColumns() {
        val table = Spreadsheet("hola,hello\nadiós,goodbye\n")
        assertEquals(',', table.separator)
        assertFalse(table.rows.header)
        val made = notes(table)
        assertEquals(
            listOf(mapOf("1" to "hola", "2" to "hello"), mapOf("1" to "adiós", "2" to "goodbye")),
            made.notes.map {
                it.fields
            },
        )
        assertEquals(basic.id, made.notes.first().templateId)
    }

    @Test
    fun excelInEuropeSeparatesWithSemicolonsAndStartsWithAByteOrderMark() {
        val table = Spreadsheet("Front;Back\r\nHallo, Welt;Hello, world\r\n\"Zeile\r\nzwei\";two lines\r\n")
        assertEquals(';', table.separator)
        assertTrue(table.rows.header)
        assertEquals(listOf("Front", "Back"), table.rows.cells.first())
        val made = notes(table)
        assertEquals("Hallo, Welt", made.notes[0].fields["1"])
        assertEquals("Zeile\r\nzwei", made.notes[1].fields["1"])
    }

    @Test
    fun quizletsTabsAndPlecosThreeColumns() {
        assertEquals('\t', Spreadsheet("perro\tdog\ngato\tcat").separator)
        val pleco = Spreadsheet("Word\tReading\tMeaning\n你好\tnǐ hǎo\thello")
        assertEquals(vocabulary.id, pleco.rows.template(builtins).id)
        assertEquals(mapOf("1" to "你好", "2" to "nǐ hǎo", "3" to "hello"), notes(pleco).notes.single().fields)
    }

    @Test
    fun ankisTextExportIsReadWithoutItsDirectives() {
        val table = Spreadsheet("#separator:tab\n#html:true\n#tags column:3\n<b>der Hund</b>\tthe dog\tnouns\n")
        assertTrue(table.anki)
        assertTrue(table.html)
        assertEquals('\t', table.separator)
        assertEquals(listOf(listOf("<b>der Hund</b>", "the dog", "nouns")), table.rows.cells)
        // The caller turns HTML into markup.
        val t = basic
        val made = table.rows.notes(table.rows.body, listOf("1", "2", Rows.TAGS), t, "deck", "me", {
            1
        }, cell = { it.replace("<b>", "**").replace("</b>", "**") })
        assertEquals("**der Hund**", made.notes.single().fields["1"])
        assertEquals("nouns", made.notes.single().tags)
    }

    @Test
    fun raggedRowsArePaddedNotCut() {
        val table = Spreadsheet("one\ntwo,deux,zwei\nthree,trois")
        assertEquals(3, table.rows.width)
        assertEquals(
            listOf(listOf("one", "", ""), listOf("two", "deux", "zwei"), listOf("three", "trois", "")),
            table.rows.cells,
        )
    }

    @Test
    fun aSeparatorOrHeaderGivenIsKept() {
        val table = Spreadsheet("a;b,c\nd;e,f", separator = ';', header = true)
        assertEquals(';', table.separator)
        assertEquals(listOf(listOf("d", "e,f")), table.rows.body)
    }

    @Test
    fun whatRondoWritesReadsBackTheSame() {
        val written = listOf(
            note("a", mapOf("1" to "**bold**, \"quoted\"", "2" to "two\nlines"), "verbs food"),
            note("b", mapOf("1" to "{{c1::cloze}} | pipe", "2" to ""), ""),
        )
        val text = Spreadsheet(basic, written).text
        assertTrue(text.startsWith("\uFEFFFront,Back,Tags"))
        val table = Spreadsheet(text)
        assertTrue(table.rows.header)
        assertEquals(listOf("1", "2", Rows.TAGS), table.rows.columns(basic))
        val made = table.rows.notes(table.rows.body, table.rows.columns(basic), basic, "deck", "me", { 1 })
        assertEquals(written.map { it.fields.filterValues(String::isNotEmpty) }, made.notes.map { it.fields })
        assertEquals(listOf("food verbs", ""), made.notes.map { it.tags })
    }

    private fun note(id: String, fields: Map<String, String>, tags: String) =
        Note(id, "deck", basic.id, fields, 1, "me", tags = tags)
}
