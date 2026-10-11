package app.rondo.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.Uuid

fun newId() = Uuid.generateV7().toString()

/** Runs [block], which never really suspends: common tests have no event loop. */
fun <T> blocking(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
}

fun deck(
    owner: String?,
    parent: String? = null,
    id: String = newId(),
    gated: Boolean = false,
    unlockAt: Int = 100,
    position: String = "V",
) = Deck(id, "Deck", position, 0, 20, 200, 90, gated, unlockAt, 1, owner, parent)

fun note(
    deck: Deck,
    template: String = Template.BASIC.id,
    fields: Map<String, String> = mapOf(
        "1" to "front",
        "2" to "back",
    ),
) = Note(newId(), deck.id, template, fields, 1, deck.ownerId)

/** Each thing's own check: decks, notes, note types, smart decks, events and settings. */
class RulesTest {
    private val alice = newId()
    private val bob = newId()
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
            assertEquals(expected, change.second.check(who, change.first), change.second.toString())
        }
    }

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
    fun smartDecks() {
        val filter = Filter(deckIds = listOf(course.id), tags = listOf("Cardio"), state = "review")
        val mine = SmartDeck("Cardio", filter, "V", 1, alice)
        assertEquals(null, mine.check(access(alice), null))
        assertEquals(ErrorCode.FORBIDDEN, mine.check(access(bob, mapOf(course.id to 2)), null))
        assertEquals(ErrorCode.OWNER_MISMATCH, mine.copy(ownerId = bob).check(access(bob), mine))
        assertEquals(ErrorCode.INVALID, mine.copy(name = " ").check(access(alice), null))
        assertEquals(ErrorCode.INVALID, mine.copy(newPerDay = -1).check(access(alice), null))
        val strange = listOf(
            filter.copy(state = "lost"),
            filter.copy(tags = listOf("two words")),
            filter.copy(deckIds = listOf("x")),
        )
        for (f in strange) assertEquals(ErrorCode.INVALID, mine.copy(filter = f).check(access(alice), null))
    }

    @Test
    fun filterLinks() {
        val filter = Filter(text = "valve", deckIds = listOf(course.id), tags = listOf("Cardio"), marked = true)
        assertEquals(filter, Filter(filter.params))
        assertEquals(emptyMap(), Filter().params)
        // What a link says that a filter can't hold is left out, so what it opens is always valid.
        val odd = Filter(
            mapOf(
                "q" to listOf("  "),
                "deck" to listOf("x"),
                "tag" to listOf("two words"),
                "state" to listOf("lost"),
            ),
        )
        assertEquals(Filter(tags = listOf("two_words")), odd)
        assertEquals(Filter(deckIds = listOf(course.id)), Filter().withDeck(course.id).withMarked(false).withText(" "))
    }

    @Test
    fun templatesEventsAndSettings() {
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

        val review = Event.Review(newId(), alice, newId(), 1, 1_000, rating = 3)
        assertEquals(null, review.check(access(alice), 1_000))
        assertEquals(ErrorCode.FORBIDDEN, review.check(access(bob), 1_000))
        assertEquals(
            ErrorCode.INVALID,
            Event.Review(review.id, alice, review.subjectId, 1, 1_000, rating = 5).check(access(alice), 1_000),
        )
        assertEquals(
            ErrorCode.CLOCK_AHEAD,
            Event.Review(review.id, alice, review.subjectId, 1, 3_600_000, rating = 3).check(access(alice), 1_000),
        )

        val settings = Settings(alice, 4, 0, 100, 1)
        assertEquals(null, settings.check(access(alice)))
        assertEquals(ErrorCode.INVALID, settings.copy(grading = 3).check(access(alice)))
        assertEquals(ErrorCode.INVALID, settings.copy(fsrsWeights = "1,2,3").check(access(alice)))
        assertEquals(ErrorCode.INVALID, settings.copy(reminderAt = 24 * 60).check(access(alice)))
        assertEquals(null, settings.copy(reminderAt = 18 * 60 + 30).check(access(alice)))
        assertEquals(Fsrs.DEFAULT, settings.copy(fsrsWeights = Fsrs.DEFAULT.joinToString(",")).weights)
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
        assertEquals(Hlc.EPOCH + 5_000, (first ushr 10) + Hlc.EPOCH)
        now -= 1_000
        assertEquals(true, a.next() > second)
        val year2300 = Hlc(15) { 10_413_792_000_000 }.next()
        assertEquals(true, year2300 < (1L shl 53))
        val server = Hlc(0) { Clock.System.now().toEpochMilliseconds() }
        assertEquals(true, server.ahead(Hlc(0) { 9_999_999_999_999 }.next()))
    }

    @Test
    fun positions() {
        val keys = mutableListOf<String>()
        val random = kotlin.random.Random(1)
        repeat(300) {
            val i = random.nextInt(keys.size + 1)
            keys.add(i, Position(keys.getOrNull(i - 1), keys.getOrNull(i)).key)
        }
        assertEquals(keys.sorted(), keys)
        assertEquals(true, keys.all { Position(it).valid })
        val between = Position("V", "W").key
        assertEquals(true, between > "V" && between < "W" && Position(between).valid)
    }

    @Test
    fun datedIdsKeepTheirOrder() {
        val ids = DatedIds(1_700_000_000_000)
        val made = List(5) { ids.next() }
        assertEquals(made.sorted(), made)
        val sameMoment = List(3) { ids.next(at = 1_600_000_000_000) }
        assertEquals(sameMoment.sorted(), sameMoment)
    }
}
