package app.rondo.core

import app.rondo.core.model.Deck
import app.rondo.core.model.ErrorCode
import app.rondo.core.model.Event
import app.rondo.core.model.Note
import app.rondo.core.model.Settings
import kotlin.test.Test
import kotlin.test.assertEquals

fun deck(
    owner: String?,
    parent: String? = null,
    id: String = Ids.new(),
    gated: Boolean = false,
    unlockAt: Int = 100,
    position: String = "V",
) = Deck(id, "Deck", position, 0, 20, 200, 90, gated, unlockAt, 1, owner, parent)

fun note(
    deck: Deck,
    template: String = Templates.BASIC,
    fields: Map<String, String> = mapOf(
        "1" to "front",
        "2" to "back",
    ),
) = Note(Ids.new(), deck.id, template, fields, 1, deck.ownerId)

class RulesTest {
    private val alice = Ids.new()
    private val bob = Ids.new()
    private val course = deck(alice)
    private val lesson = deck(alice, course.id)
    private val other = deck(alice)
    private val orphan = deck(null)
    private val tree = Tree(listOf(course, lesson, other, orphan))

    private fun access(who: String, lends: Map<String, Int> = emptyMap(), pro: Boolean = true) =
        Access(who, tree, lends) { pro }

    @Test
    fun roles() {
        assertEquals(Role.OWNER, access(alice).role(lesson.id))
        assertEquals(Role.EDITOR, access(bob, mapOf(course.id to 2)).role(lesson.id))
        assertEquals(Role.VIEWER, access(bob, mapOf(course.id to 2), pro = false).role(lesson.id))
        assertEquals(Role.VIEWER, access(bob, mapOf(course.id to 1)).role(lesson.id))
        assertEquals(Role.NONE, access(bob, mapOf(course.id to 2)).role(other.id))
        assertEquals(Role.READ_ONLY, access(alice).role(orphan.id))
    }

    @Test
    fun decks() {
        val editor = access(bob, mapOf(course.id to 2))
        val cases = listOf(
            Triple(access(alice), null to deck(alice, lesson.id), null),
            Triple(access(alice), null to deck(bob), ErrorCode.OWNER_MISMATCH),
            Triple(editor, null to deck(alice, lesson.id), null),
            Triple(editor, null to deck(bob, lesson.id), ErrorCode.OWNER_MISMATCH),
            Triple(editor, null to deck(alice, other.id), ErrorCode.FORBIDDEN),
            Triple(access(bob, mapOf(course.id to 1)), null to deck(alice, lesson.id), ErrorCode.FORBIDDEN),
            Triple(access(alice), course to course.copy(parentId = lesson.id), ErrorCode.CYCLE),
            Triple(access(alice), course to course.copy(ownerId = bob), ErrorCode.OWNER_MISMATCH),
            Triple(editor, course to course.copy(deletedAt = 1), ErrorCode.FORBIDDEN),
            Triple(editor, lesson to lesson.copy(parentId = null), ErrorCode.FORBIDDEN),
            Triple(editor, lesson to lesson.copy(name = "Renamed"), null),
            Triple(access(alice), orphan to orphan.copy(name = "x"), ErrorCode.READ_ONLY),
            Triple(access(alice), null to deck(alice).copy(name = " "), ErrorCode.INVALID),
            Triple(access(alice), null to deck(alice, unlockAt = 95), ErrorCode.INVALID),
        )
        for ((who, change, expected) in cases) {
            assertEquals(
                expected,
                Rules.deck(who, change.first, change.second),
                change.second.toString(),
            )
        }
    }

    @Test
    fun notes() {
        val editor = access(bob, mapOf(course.id to 2))
        val basic = Templates.builtin(Templates.BASIC)!!
        val n = note(lesson)
        assertEquals(null, Rules.note(editor, null, n, basic))
        assertEquals(ErrorCode.OWNER_MISMATCH, Rules.note(editor, null, n.copy(ownerId = bob), basic))
        assertEquals(ErrorCode.FORBIDDEN, Rules.note(editor, n, n.copy(deckId = other.id), basic))
        assertEquals(ErrorCode.INVALID, Rules.note(access(alice), null, n.copy(fields = mapOf("9" to "x")), basic))
        val anki = basic.copy(id = Ids.new(), ownerId = alice, kind = Templates.ANKI)
        assertEquals(ErrorCode.READ_ONLY, Rules.note(access(alice), n, n.copy(fields = mapOf("1" to "changed")), anki))
        assertEquals(null, Rules.note(access(alice), n, n.copy(deckId = course.id), anki))
    }

    @Test
    fun templatesEventsAndSettings() {
        val custom = Templates.builtin(Templates.BASIC)!!.copy(id = Ids.new(), ownerId = alice)
        assertEquals(null, Rules.template(access(alice), null, custom))
        assertEquals(
            ErrorCode.FORBIDDEN,
            Rules.template(access(bob, mapOf(course.id to 2)), custom, custom.copy(name = "x")),
        )
        assertEquals(
            ErrorCode.INVALID,
            Rules.template(access(alice), null, custom.copy(def = custom.def.copy(cards = emptyList()))),
        )

        val review = Event(Ids.new(), alice, Ids.new(), 1, Learning.REVIEW, 1_000, value = 3)
        assertEquals(null, Rules.event(access(alice), review, 1_000))
        assertEquals(ErrorCode.FORBIDDEN, Rules.event(access(bob), review, 1_000))
        assertEquals(ErrorCode.INVALID, Rules.event(access(alice), review.copy(value = 5), 1_000))
        assertEquals(ErrorCode.CLOCK_AHEAD, Rules.event(access(alice), review.copy(at = 3_600_000), 1_000))

        val settings = Settings(alice, 4, 0, 100, 1)
        assertEquals(null, Rules.settings(access(alice), settings))
        assertEquals(ErrorCode.INVALID, Rules.settings(access(alice), settings.copy(grading = 3)))
        assertEquals(ErrorCode.INVALID, Rules.settings(access(alice), settings.copy(fsrsWeights = "1,2,3")))
        assertEquals(ErrorCode.INVALID, Rules.settings(access(alice), settings.copy(reminderAt = 24 * 60)))
        assertEquals(null, Rules.settings(access(alice), settings.copy(reminderAt = 18 * 60 + 30)))
    }

    @Test
    fun clocksArePackedAndOrdered() {
        var now = Hlc.EPOCH + 5_000
        val a = Hlc(1) { now }
        val b = Hlc(2) { now }
        val first = a.next()
        val second = a.next()
        b.observe(second)
        assertEquals(true, second > first && b.next() > second)
        assertEquals(Hlc.EPOCH + 5_000, Hlc.millis(first))
        now -= 1_000
        assertEquals(true, a.next() > second)
        val year2300 = Hlc(15) { 10_413_792_000_000 }.next()
        assertEquals(true, year2300 < (1L shl 53))
        assertEquals(true, Rules.clockAhead(Hlc(0) { 9_999_999_999_999 }.next(), nowMillis()))
    }

    @Test
    fun positions() {
        val keys = mutableListOf<String>()
        val random = kotlin.random.Random(1)
        repeat(300) {
            val i = random.nextInt(keys.size + 1)
            keys.add(i, Positions.between(keys.getOrNull(i - 1), keys.getOrNull(i)))
        }
        assertEquals(keys.sorted(), keys)
        assertEquals(true, keys.all(Positions::isValid))
        val between = Positions.between("V", "W")
        assertEquals(true, between > "V" && between < "W" && Positions.isValid(between))
        val spread = Positions.spread(500)
        assertEquals(spread.sorted(), spread)
        assertEquals(500, spread.toSet().size)
        assertEquals(true, spread.all { Positions.isValid(it) && it.length <= 2 })
    }
}
