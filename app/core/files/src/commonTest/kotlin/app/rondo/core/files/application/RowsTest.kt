package app.rondo.core.files.application

import app.rondo.core.notes.domain.Template
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Rows becoming notes: the note type they fill, where each column goes, and what's left out. */
class RowsTest {
    private val basic = Template.BASIC
    private val builtins = Template.builtins.values.toList()
    private var clock = 0L

    private fun notes(rows: Rows, existing: Set<String> = emptySet()): Made {
        val t = rows.template(builtins)
        return rows.notes(rows.body, rows.columns(t), t, "deck", "me", { ++clock }, existing, now = 1_000_000)
    }

    @Test
    fun clozesPickTheClozeTemplate() {
        val rows = Rows(listOf(listOf("The capital of {{c1::France}} is {{c2::Paris}}", "Geography")))
        assertEquals(Template.CLOZE.id, rows.template(builtins).id)
        assertEquals(
            mapOf("1" to "The capital of {{c1::France}} is {{c2::Paris}}", "2" to "Geography"),
            notes(rows).notes.single().fields,
        )
    }

    @Test
    fun columnTitlesAnAssistantWritesMapByMeaning() {
        val rows = Rows(
            listOf(
                listOf("Question", "Answer", "Tags"),
                listOf("**Mitochondria**", "The cell's power plant", "biology cells"),
                listOf("ATP", "Energy", "biology, chemistry"),
            ),
        )
        assertTrue(rows.header)
        assertEquals(listOf("1", "2", Rows.TAGS), rows.columns(basic))
        val made = notes(rows)
        assertEquals("**Mitochondria**", made.notes[0].fields["1"])
        assertEquals("biology cells", made.notes[0].tags)
        assertEquals("biology chemistry", made.notes[1].tags)
    }

    @Test
    fun duplicatesAndRowsWithoutCardsAreSkippedAndCounted() {
        val rows = Rows(
            listOf(listOf("hola", "hello"), listOf("", "only a back"), listOf("Hola", "again"), listOf("nuevo", "new")),
        )
        val made = notes(rows, existing = setOf(basic.key(mapOf("1" to "nuevo"))!!))
        assertEquals(listOf("hola"), made.notes.map { it.fields["1"] })
        assertEquals(
            listOf(2 to Skipped.EMPTY, 3 to Skipped.DUPLICATE, 4 to Skipped.DUPLICATE),
            made.skipped.map { it.row to it.why },
        )
    }

    @Test
    fun notesKeepTheRowsOrder() {
        val made = notes(Rows((1..50).map { listOf("word $it", "meaning $it") }))
        assertEquals(made.notes.map { it.id }.sorted(), made.notes.map { it.id })
    }
}
