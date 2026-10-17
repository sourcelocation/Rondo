package app.rondo.core.notes.domain

import app.rondo.core.decks.domain.Access
import app.rondo.core.decks.domain.Tree
import app.rondo.core.kernel.domain.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals

/** Notes' and note types' own rules, and the text notes are found by. */
class NotesTest {
    private val alice = newId()
    private val bob = newId()
    private val course = deck(alice)
    private val lesson = deck(alice, course.id)
    private val other = deck(alice)
    private val tree = Tree(listOf(course, lesson, other))

    private fun access(who: String, lends: Map<String, Int> = emptyMap()) = Access(who, tree, lends) { true }

    @Test
    fun notes() {
        val editor = access(bob, mapOf(course.id to 2))
        val basic = Template.BASIC
        val n = note(lesson)
        assertEquals(null, n.check(editor, null, basic))
        assertEquals(ErrorCode.OWNER_MISMATCH, n.copy(ownerId = bob).check(editor, null, basic))
        assertEquals(ErrorCode.FORBIDDEN, n.copy(deckId = other.id).check(editor, n, basic))
        assertEquals(ErrorCode.INVALID, n.copy(fields = mapOf("9" to "x")).check(access(alice), null, basic))
        val anki = AnkiTemplate(
            newId(),
            "Basic",
            basic.fields,
            listOf(AnkiCardDef(1, "Card", "{{Front}}", "{{Back}}")),
            "",
            ownerId = alice,
        )
        assertEquals(ErrorCode.READ_ONLY, n.copy(fields = mapOf("1" to "changed")).check(access(alice), n, anki))
        assertEquals(null, n.copy(deckId = course.id).check(access(alice), n, anki))
        // Tags change on Anki's notes too: they aren't fields.
        assertEquals(null, n.copy(tags = "Cardio::Arrhythmia pathoma").check(access(alice), n, anki))
        assertEquals(ErrorCode.INVALID, n.copy(tags = "two  spaces").check(access(alice), null, basic))
        assertEquals(ErrorCode.INVALID, n.copy(tags = "a::::b").check(access(alice), null, basic))
        assertEquals(ErrorCode.INVALID, n.copy(tags = "x".repeat(201)).check(access(alice), null, basic))
    }

    @Test
    fun noteTypes() {
        val custom = Template.BASIC.copy(id = newId(), ownerId = alice)
        assertEquals(null, custom.check(access(alice), null))
        assertEquals(ErrorCode.FORBIDDEN, custom.copy(name = "x").check(access(bob, mapOf(course.id to 2)), custom))
        assertEquals(ErrorCode.INVALID, custom.copy(cards = emptyList()).check(access(alice), null))
        // Anki's note types keep their HTML; only their name changes.
        val anki =
            AnkiTemplate(
                newId(),
                "Anki",
                custom.fields,
                listOf(AnkiCardDef(1, "Card", "{{Front}}", "{{Back}}")),
                "",
                ownerId = alice,
            )
        assertEquals(null, anki.copy(name = "Renamed").check(access(alice), anki))
        assertEquals(ErrorCode.READ_ONLY, anki.copy(css = ".card {}").check(access(alice), anki))
    }

    @Test
    fun searchTextIsEveryTextFieldFolded() {
        val n = note(deck(newId()), fields = mapOf("1" to "**Ab**", "2" to "{{c1::Cd}}"))
        assertEquals("ab cd", Template.BASIC.searchText(n))
    }
}
