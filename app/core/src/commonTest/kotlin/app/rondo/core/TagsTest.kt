package app.rondo.core

import app.rondo.core.model.Filter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TagsTest {
    @Test
    fun tagsAreTidiedSortedAndKeptOnce() {
        val tags = Tags.parse("  pathoma #AK::Step1::::Cardio\tPathoma marked ")
        assertEquals("#AK::Step1::Cardio marked pathoma", tags.text)
        assertEquals(listOf("#AK::Step1::Cardio", "marked", "pathoma"), tags.list)
        assertEquals(Tags.NONE, Tags.parse(" "))
        assertEquals("a_b", Tags.clean(" a  b "))
        assertEquals(null, Tags.clean("::"))
        assertEquals(Tags.MAX_COUNT, Tags.of((1..400).map { "t$it" }).list.size)
        assertEquals(Tags.NONE, Tags.of(listOf("x".repeat(Tags.MAX_LENGTH + 1))))
    }

    @Test
    fun addingRemovingAndLookingUnder() {
        val tags = Tags.parse("Cardio::Arrhythmia renal")
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
    fun theRulesAskForWellFormedTags() {
        assertTrue(Tags.isWellFormed(""))
        assertTrue(Tags.isWellFormed("b a"))
        assertTrue(Tags.isWellFormed(Tags.parse("whatever  is ::typed:: here").text))
        assertFalse(Tags.isWellFormed(" a"))
        assertFalse(Tags.isWellFormed("a::"))
    }

    @Test
    fun aFilterAsPickedFindsDecksBelowAndNeverDeletedOnes() {
        val me = Ids.new()
        val course = deck(me)
        val lesson = deck(me, course.id)
        val gone = deck(me).copy(deletedAt = 1)
        val under = deck(me, gone.id)
        val tree = Tree(listOf(course, lesson, gone, under))

        assertEquals(setOf(course.id, lesson.id), Filter(deckIds = listOf(course.id)).notes(tree).decks.toSet())
        // Nothing picked: every live deck; picked decks that are all gone: nothing, not everything.
        assertEquals(setOf(course.id, lesson.id), Filter().notes(tree).decks.toSet())
        assertEquals(listOf(Search.NOWHERE), Filter(deckIds = listOf(gone.id)).notes(tree).decks)
        assertEquals(listOf("a"), Filter(tags = listOf("a"), marked = true).notes(tree).tags)
    }
}
