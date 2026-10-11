package app.rondo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TagsTest {
    @Test
    fun tagsAreTidiedSortedAndKeptOnce() {
        val tags = Tags("  pathoma #AK::Step1::::Cardio\tPathoma marked ")
        assertEquals("#AK::Step1::Cardio marked pathoma", tags.text)
        assertEquals(listOf("#AK::Step1::Cardio", "marked", "pathoma"), tags.list)
        assertEquals(Tags.NONE, Tags(" "))
        assertEquals("a_b", Tag(" a  b ").text)
        assertEquals(null, Tag("::").text)
        assertEquals(Tags.MAX_COUNT, Tags((1..400).map { "t$it" }).list.size)
        assertEquals(Tags.NONE, Tags(listOf("x".repeat(Tags.MAX_LENGTH + 1))))
    }

    @Test
    fun addingRemovingAndLookingUnder() {
        val tags = Tags("Cardio::Arrhythmia renal")
        assertEquals(
            "Cardio::Arrhythmia Cardio::Valves renal",
            (tags + listOf("cardio::arrhythmia", "Cardio::Valves")).text,
        )
        assertEquals("Cardio::Arrhythmia", (tags - listOf("RENAL", "Cardio")).text)
        assertTrue(tags.has("cardio"))
        assertTrue(tags.has("Cardio::Arrhythmia"))
        assertFalse(tags.has("Cardi"))
        assertFalse(tags.has("Cardio::Arrhythmia::AF"))
    }

    @Test
    fun aNoteAsksForWellFormedTags() {
        val me = newId()
        val d = deck(me)
        val access = Access(me, Tree(listOf(d)), emptyMap()) { true }
        fun check(tags: String) = note(d).copy(tags = tags).check(access, null, Template.BASIC)
        assertEquals(null, check(""))
        assertEquals(null, check("a b"))
        assertEquals(null, check(Tags("whatever  is ::typed:: here").text))
        assertEquals(ErrorCode.INVALID, check(" a"))
        assertEquals(ErrorCode.INVALID, check("a::"))
    }

    @Test
    fun aFilterAsPickedFindsDecksBelowAndNeverDeletedOnes() {
        val me = newId()
        val course = deck(me)
        val lesson = deck(me, course.id)
        val gone = deck(me).copy(deletedAt = 1)
        val under = deck(me, gone.id)
        val tree = Tree(listOf(course, lesson, gone, under))

        assertEquals(setOf(course.id, lesson.id), Filter(deckIds = listOf(course.id)).notes(tree).decks.toSet())
        // Nothing picked: every live deck; picked decks that are all gone: nothing, not everything.
        assertEquals(setOf(course.id, lesson.id), Filter().notes(tree).decks.toSet())
        assertEquals(listOf(NoteFilter.NOWHERE), Filter(deckIds = listOf(gone.id)).notes(tree).decks)
        assertEquals(listOf("a"), Filter(tags = listOf("a"), marked = true).notes(tree).tags)
    }
}
