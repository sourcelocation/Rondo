package app.rondo.client

import app.rondo.client.services.Download
import app.rondo.client.services.FileFormat
import app.rondo.client.services.Overview
import app.rondo.core.Access
import app.rondo.core.Counts
import app.rondo.core.Deck
import app.rondo.core.Filter
import app.rondo.core.NoteSort
import app.rondo.core.Role
import app.rondo.core.SmartDeck
import app.rondo.core.Tree
import kotlin.js.JsExport

/** What can be done to a deck, as its menus offer it: see [DeckItem.actions]. */
@JsExport
enum class DeckAction {
    STUDY,
    SETTINGS,
    SHARE,
    BROWSE,
    ADD_NOTE,
    NEW_SUBDECK,
    RENAME,
    MOVE,
    DUPLICATE,
    CONVERT,
    EXPORT,
    LEAVE,
    DELETE,
}

/**
 * A deck as screens show it: its place in the tree, its ring and level, today's counts, and what it
 * offers you. Deck [d] in [o], at [depth] in its list; [anki]: the decks with notes in Anki's format;
 * [from]: who lent each deck.
 */
@JsExport
class DeckItem internal constructor(
    o: Overview,
    d: Deck,
    val depth: Int,
    access: Access,
    anki: Set<String>,
    from: Map<String, String?> = emptyMap(),
) {
    private val opens = o.levels[d.id]
    private val counts = o.counts(d.id)
    val id = d.id
    val name = d.name
    val icon = d.icon
    val color = d.color
    val parentId = d.parentId
    val children = o.tree.children(d.id).size

    /** Learned percent; for a locked level, progress towards opening it. */
    val ring = opens?.ring ?: 0
    val locked = opens?.open == false
    val level = opens?.takeIf { it.of > 0 }?.let { strings.levelOf(it.index, it.of) }
    val newCount = counts.new
    val learning = counts.learning
    val review = counts.review

    /** Who lent it, on a shared deck at the top. */
    val from = from[d.id]

    /** Notes in it and its sub-decks. */
    val notes = o.tree.subtree(d.id).sumOf { o.loads[it.id]?.notes ?: 0 }

    /** Today's counts as a line ("3 new · 1 to review"), or what it waits for. */
    val detail = when {
        locked -> o.tree.children(d.parentId ?: "").let { it.getOrNull(it.indexOf(d) - 1) }
            ?.let { strings.opensAfter(it.name) } ?: strings.locked

        notes == 0 -> strings.emptyDeck

        else -> strings.toStudy(counts.total, notes)
    }

    /** What it offers, in order, for who you are to it. */
    val actions: Array<DeckAction> = run {
        val owner = access.role(d.id) == Role.OWNER
        val write = access.canWrite(d.id) == null
        listOfNotNull(
            DeckAction.STUDY.takeIf { notes > 0 },
            DeckAction.SETTINGS.takeIf { write },
            DeckAction.SHARE.takeIf { owner },
            DeckAction.BROWSE,
            DeckAction.ADD_NOTE.takeIf { write },
            DeckAction.NEW_SUBDECK.takeIf { write },
            DeckAction.RENAME.takeIf { write },
            DeckAction.MOVE.takeIf { owner },
            DeckAction.DUPLICATE,
            DeckAction.CONVERT.takeIf { o.tree.subtree(d.id).any { it.id in anki } },
            DeckAction.EXPORT,
            DeckAction.LEAVE.takeIf { d.id in o.lends },
            DeckAction.DELETE.takeIf { owner },
        ).toTypedArray()
    }
}

/** A smart deck in a list: its look and what it offers today. */
@JsExport
class SmartDeckItem internal constructor(
    val id: String,
    val name: String,
    val icon: String?,
    val color: Int,
    val newCount: Int,
    val learning: Int,
    val review: Int,
    /** Today's counts as a line, or that nothing is due. */
    val detail: String,
) {
    internal constructor(s: SmartDeck, c: Counts) : this(
        s.id,
        s.name,
        s.icon,
        s.color,
        c.new,
        c.learning,
        c.review,
        if (c.total >
            0
        ) {
            strings.due(c.total)
        } else {
            strings.nothingDue
        },
    )
}

@JsExport
class Removed internal constructor(val id: String, val deck: Boolean, val label: String, val deletedAt: Double)

@JsExport
class HomeState internal constructor(
    val mine: Array<DeckItem>,
    val shared: Array<DeckItem>,
    /** Cards they pick are in the decks above too, so they don't add to [due]. */
    val smart: Array<SmartDeckItem>,
    val deleted: Array<Removed>,
    /** Cards due today across every deck. */
    val due: Int,
    /** Cards answered today. */
    val studied: Int,
    /** Reviews due tomorrow. */
    val tomorrow: Int,
    /** Days in a row with reviews, today included once it has some. */
    val streak: Int,
    /** Answers on each of the last seven days, today last. */
    val week: Array<Int>,
    /** The top card's headline and line under it: what today looks like. */
    val headline: String,
    val summary: String,
    /** Levels that just opened. */
    val opened: Array<String>,
)

/**
 * Your decks and the ones shared with you, as trees, with today's plan above and Recently deleted
 * below. Every UI keeps it open for its sidebar, so where decks are in the tree changes here: a
 * new deck, and moving one.
 */
@JsExport
class Home internal constructor(override val app: App) : Screen<HomeState>() {
    private val library get() = app.rondo.library
    private var tree = Tree(emptyList())

    override suspend fun load(): HomeState {
        val o = library.overview()
        tree = o.tree
        val access = library.access()
        val anki = library.ankiDecks()
        val (mine, shared) = o.tree.roots.partition { it.ownerId == library.me }
        val from = library.lenders()
        // Recently deleted: decks that aren't inside another deleted one, and notes in decks still there.
        val decks = o.tree.byId.values.filter { d ->
            d.deletedAt != null && d.ownerId == library.me && o.tree.ancestors(d.id).none { it.deletedAt != null }
        }
        val notes = library.deletedNotes()
        val templates = library.templates()
        val deleted = decks.map { Removed(it.id, true, it.name, it.deletedAt!!.toDouble()) } +
            notes.filter { !o.tree.dead(it.deckId) }.map {
                Removed(it.id, false, templates[it.templateId]?.label(it) ?: "…", it.deletedAt!!.toDouble())
            }
        val study = app.rondo.study
        val activity = study.activity()
        val day = study.day(o)
        val smart = library.smartDecks().map { SmartDeckItem(it, study.counts(it, day)) }
        val due = o.tree.roots.sumOf { o.counts(it.id).total }
        val studied = activity.days.last()
        val (headline, summary) = when {
            o.tree.roots.isEmpty() -> strings.homeEmpty to strings.homeEmptyHint
            due > 0 && studied > 0 -> strings.keepGoing to strings.due(due)
            due > 0 -> strings.readyToday to strings.due(due)
            studied > 0 -> strings.doneToday to strings.tomorrow(activity.tomorrow)
            else -> strings.nothingDue to strings.tomorrow(activity.tomorrow)
        }
        return HomeState(
            items(o, mine, access, anki),
            items(o, shared, access, anki, from),
            smart.toTypedArray(),
            deleted.sortedByDescending { it.deletedAt }.toTypedArray(),
            due, studied, activity.tomorrow, activity.streak, activity.days.takeLast(7).toTypedArray(),
            headline, summary,
            o.opened.mapNotNull { o.tree[it]?.name }.toTypedArray(),
        )
    }

    /** [roots] and the decks under each, in order. */
    private fun items(
        o: Overview,
        roots: List<Deck>,
        access: Access,
        anki: Set<String>,
        from: Map<String, String?> = emptyMap(),
    ): Array<DeckItem> {
        val out = ArrayList<DeckItem>()
        fun walk(d: Deck, depth: Int) {
            out += DeckItem(o, d, depth, access, anki, from)
            o.tree.children(d.id).forEach { walk(it, depth + 1) }
        }
        roots.forEach { walk(it, 0) }
        return out.toTypedArray()
    }

    /** A new deck under [parentId] (null: the top level) with its look; [then] gets its id. */
    fun create(name: String, parentId: String?, icon: String?, color: Int, then: (String) -> Unit) = act {
        if (name.isBlank()) return@act
        val deck = library.createDeck(name, parentId)
        val look = icon?.ifBlank { null }
        if (look != null || color != 0) library.updateDeck(deck.id) { it.copy(icon = look, color = color) }
        then(deck.id)
    }

    /** Where deck [id] can go: the top level, or any of your decks outside it. */
    fun targets(id: String): Array<Choice> {
        val inside = tree.subtree(id).map { it.id }.toSet()
        val decks = tree.byId.values.filter { it.ownerId == library.me && it.id !in inside && !tree.dead(it.id) }
        return (listOf(Choice("", strings.topLevel)) + choices(tree, decks)).toTypedArray()
    }

    /**
     * Moves deck [id] under [parentId] (null: the top level), before [beforeId] or last. A move that
     * takes it out of a deck others borrow asks first: [ask] gets the question, and the UI calls
     * again with [confirmed] once the person agrees.
     */
    fun move(id: String, parentId: String?, beforeId: String?, confirmed: Boolean, ask: (String) -> Unit) = act {
        val lending = app.rondo.account.me?.lending.orEmpty().toSet()
        val staying = (tree.ancestors(parentId ?: "") + listOfNotNull(tree[parentId])).map { it.id }.toSet()
        val leaving = tree.ancestors(id).firstOrNull { it.id in lending && it.id !in staying }
        val deck = tree[id] ?: return@act
        if (leaving != null && !confirmed) return@act ask(strings.moveOut(deck.name, leaving.name))
        val parent = parentId?.takeIf { it.isNotEmpty() }
        if (deck.parentId == parent && (beforeId == id || beforeId == tree.next(id)?.id)) return@act
        library.move(id, parent, beforeId)
    }

    fun restore(id: String, deck: Boolean) = act { if (deck) library.restore(id) else library.restoreNote(id) }
}

@JsExport
class DeckState internal constructor(
    /** Null: it's gone. */
    val deck: DeckItem?,
    val path: Array<String>,
    val description: String?,
    val gated: Boolean,
    val children: Array<DeckItem>,
    /** Each sub-deck's requirement: the percent of the one before it (the first has none). */
    val unlockAt: Array<Int>,
    val notes: Int,
    val learned: Int,
    val canEdit: Boolean,
    /** Its author removed it. */
    val frozen: Boolean,
    /** What its menu offers: the deck's actions, but those the page shows as buttons. */
    val menu: Array<DeckAction>,
) {
    /** A deck that's gone. */
    internal constructor() :
        this(null, emptyArray(), null, false, emptyArray(), emptyArray(), 0, 0, false, false, emptyArray())
}

/**
 * A deck: what its page shows (its header and menu, its notes, its sub-decks and levels) and what
 * can be done to it. It reads nothing until it's watched, so any menu of the deck acts through one.
 */
@JsExport
class DeckScreen internal constructor(override val app: App, val id: String) : Screen<DeckState>() {
    private val library get() = app.rondo.library

    override suspend fun load(): DeckState {
        val o = library.overview()
        val access = library.access()
        val d = o.tree[id]
        if (d == null || o.tree.dead(id)) {
            return DeckState()
        }
        val anki = library.ankiDecks()
        val kids = o.tree.children(id)
        val loads = o.tree.subtree(id).mapNotNull { o.loads[it.id] }
        val item = DeckItem(o, d, 0, access, anki)
        return DeckState(
            item, o.tree.path(id).toTypedArray(), d.description, d.gated,
            kids.map { DeckItem(o, it, 1, access, anki) }.toTypedArray(), kids.map { it.unlockAt }.toTypedArray(),
            loads.sumOf { it.notes }, loads.sumOf { it.learnedNotes }, access.canWrite(id) == null, d.ownerId == null,
            item.actions.filter { it !in ON_PAGE }.toTypedArray(),
        )
    }

    fun rename(name: String) = act {
        if (name.isNotBlank()) library.updateDeck(id) { it.copy(name = name.trim()) }
    }

    /** Copies it into your own decks; [then] gets the copy's id. */
    fun copy(then: (String) -> Unit) = act { then(library.copy(id)) }

    /** An editable copy of a deck kept in Anki's format; [then] gets its id. */
    fun convert(then: (String) -> Unit) = act { then(library.copy(id, convert = true)) }

    fun delete() = act { library.delete(id) }

    /** Stops borrowing it (lent to you); [then] runs once it's gone. */
    fun leave(then: () -> Unit) = act {
        app.leave(id)
        then()
    }

    /** Whether its sub-decks are levels, opening in order. */
    fun setGated(gated: Boolean) = act { library.updateDeck(id) { it.copy(gated = gated) } }

    /** How much of the sub-deck before [childId] must be learned for it to open: 50 to 100, by tens. */
    fun setUnlockAt(childId: String, percent: Int) = act { library.updateDeck(childId) { it.copy(unlockAt = percent) } }

    /** Opens a locked level now, whatever the one before it says. */
    fun unlock(childId: String) = act { library.unlock(childId) }

    private companion object {
        /** What the page offers as buttons of its own, so its menu doesn't. */
        val ON_PAGE = setOf(DeckAction.STUDY, DeckAction.BROWSE, DeckAction.ADD_NOTE, DeckAction.NEW_SUBDECK)
    }
}

@JsExport
class DeckSettingsState internal constructor(
    val name: String,
    val icon: String?,
    val color: Int,
    val description: String?,
    val language: String?,
    val newPerDay: Int,
    val reviewsPerDay: Int,
    val retention: Int,
    /** The retention it can aim for, from [minRetention] to [maxRetention] percent: see [Deck.RETENTION]. */
    val minRetention: Int,
    val maxRetention: Int,
    /** It's gone, or no longer yours to change. */
    val gone: Boolean,
)

/** A deck's settings: everything set once and rarely changed. Opened from the deck's page or any menu of it. */
@JsExport
class DeckSettingsScreen internal constructor(override val app: App, val deckId: String) :
    Screen<DeckSettingsState>() {
    private val library get() = app.rondo.library

    override suspend fun load(): DeckSettingsState {
        val tree = library.tree()
        val d = tree[deckId]?.takeIf { !tree.dead(deckId) && library.access().canWrite(deckId) == null }
        val r = Deck.RETENTION
        if (d == null) return DeckSettingsState("", null, 0, null, null, 0, 0, 0, r.first, r.last, true)
        return DeckSettingsState(
            d.name, d.icon, d.color, d.description, d.language, d.newPerDay, d.reviewsPerDay, d.retention, r.first,
            r.last, false,
        )
    }

    /** Changes one thing about the deck, as it's stored now: changes made together never undo each other. */
    private fun change(change: (Deck) -> Deck) = act { library.updateDeck(deckId, change) }

    /** A blank name keeps the one it has. */
    fun setName(name: String) = change { if (name.isBlank()) it else it.copy(name = name.trim()) }

    fun setIcon(icon: String?) = change { it.copy(icon = icon?.ifBlank { null }) }

    fun setColor(color: Int) = change { it.copy(color = color) }

    fun setDescription(description: String) = change { it.copy(description = description.trim().ifBlank { null }) }

    /** A BCP 47 code; null: none. */
    fun setLanguage(language: String?) = change { it.copy(language = language?.ifBlank { null }) }

    /** Kept within what a deck can give: see [Deck.NEW_PER_DAY]. */
    fun setNewPerDay(newPerDay: Int) = change { it.copy(newPerDay = newPerDay.coerceIn(Deck.NEW_PER_DAY)) }

    /** Kept within what a deck can give: see [Deck.REVIEWS_PER_DAY]. */
    fun setReviewsPerDay(reviewsPerDay: Int) =
        change { it.copy(reviewsPerDay = reviewsPerDay.coerceIn(Deck.REVIEWS_PER_DAY)) }

    /** In percent, kept within [Deck.RETENTION]. */
    fun setRetention(retention: Int) = change { it.copy(retention = retention.coerceIn(Deck.RETENTION)) }
}

@JsExport
class ExportState internal constructor(
    val formats: Array<FileFormat>,
    /** The format being made now, if one is. */
    val busy: FileFormat?,
    val gone: Boolean,
)

/** A deck as a file to save. Anyone who can copy a deck can export it. Opened from the deck's page or any menu of it. */
@JsExport
class ExportScreen internal constructor(override val app: App, val deckId: String) : Screen<ExportState>() {
    private var busy: FileFormat? = null

    override suspend fun load(): ExportState {
        val tree = app.rondo.library.tree()
        val formats = arrayOf(FileFormat.RONDO, FileFormat.CSV, FileFormat.ANKI)
        return ExportState(formats, busy, tree[deckId] == null || tree.dead(deckId))
    }

    /**
     * Makes the file as [format]; with [progress] (when it [FileFormat.keepsProgress]), where your
     * cards stand and your reviews. [then] gets the file, or null when it couldn't be made (the
     * notice says why).
     */
    fun run(format: FileFormat, progress: Boolean, then: (Download?) -> Unit) = act {
        busy = format
        reload()
        var file: Download? = null
        try {
            file = app.rondo.exports.run(deckId, format, progress)
        } finally {
            busy = null
            reload()
            then(file)
        }
    }
}

@JsExport
class SmartDeckState internal constructor(
    val item: SmartDeckItem,
    val newPerDay: Int,
    val reviewsPerDay: Int,
    val gone: Boolean,
)

/**
 * One smart deck: what it offers today, its settings, and its rules: a filter, changed here and kept
 * as it changes, above the notes it finds. Its cards stay in their decks, scheduled by them.
 */
@JsExport
class SmartDeckScreen internal constructor(override val app: App, val id: String) : Screen<SmartDeckState>() {
    private val library get() = app.rondo.library

    /** Its rules, and the notes they find. */
    val notes = part(
        BrowseScreen(app, { library.smartDeck(id)?.filter ?: Filter() }, NoteSort.ADDED) { filter ->
            library.updateSmartDeck(id) { it.copy(filter = filter) }
            app.rondo.syncSoon()
        },
    )

    override suspend fun load(): SmartDeckState {
        val smart = library.smartDeck(id)?.takeIf { it.deletedAt == null }
        val day = app.rondo.study.day(library.overview())
        if (smart == null) return SmartDeckState(SmartDeckItem(id, "", null, 0, 0, 0, 0, ""), 0, 0, true)
        return SmartDeckState(
            SmartDeckItem(smart, app.rondo.study.counts(smart, day)),
            smart.newPerDay,
            smart.reviewsPerDay,
            false,
        )
    }

    /** Changes one thing about it, as it's stored now: changes made together never undo each other. */
    private fun change(change: (SmartDeck) -> SmartDeck) = act {
        library.updateSmartDeck(id, change)
        app.rondo.syncSoon()
    }

    /** A blank name keeps the one it has. */
    fun setName(name: String) = change { if (name.isBlank()) it else it.copy(name = name.trim()) }

    fun setIcon(icon: String?) = change { it.copy(icon = icon?.ifBlank { null }) }

    fun setColor(color: Int) = change { it.copy(color = color) }

    /** Kept within what a deck can give: see [Deck.NEW_PER_DAY]. */
    fun setNewPerDay(newPerDay: Int) = change { it.copy(newPerDay = newPerDay.coerceIn(Deck.NEW_PER_DAY)) }

    /** Kept within what a deck can give: see [Deck.REVIEWS_PER_DAY]. */
    fun setReviewsPerDay(reviewsPerDay: Int) =
        change { it.copy(reviewsPerDay = reviewsPerDay.coerceIn(Deck.REVIEWS_PER_DAY)) }

    /** Deletes the smart deck alone: its cards stay where they are. */
    fun delete() = act {
        library.deleteSmartDeck(id)
        app.rondo.syncSoon()
    }
}

/** Languages a deck can be in, as BCP 47 codes; UIs show their names in the reader's language. */
@JsExport
val languages: Array<String> = arrayOf(
    "ar", "cs", "da", "de", "el", "en", "es", "fa", "fi", "fr", "he", "hi", "hu", "id", "it", "ja", "ko", "la",
    "nl", "no", "pl", "pt", "ro", "ru", "sv", "th", "tr", "uk", "vi", "zh",
)
