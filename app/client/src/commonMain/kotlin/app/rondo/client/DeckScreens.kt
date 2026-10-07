package app.rondo.client

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.rondo.client.db.Notes
import app.rondo.core.Access
import app.rondo.core.Ids
import app.rondo.core.Markup
import app.rondo.core.Role
import app.rondo.core.Templates
import app.rondo.core.Tree
import app.rondo.core.model.Deck
import app.rondo.core.model.ErrorCode
import app.rondo.core.model.InviteRequest
import app.rondo.core.model.Note
import app.rondo.core.model.RoleUpdate
import kotlin.js.JsExport

/** A deck in a list: its place in the tree, its ring and level, and today's counts. */
@JsExport
class DeckItem internal constructor(
    val id: String,
    val name: String,
    val icon: String?,
    val color: Int,
    val depth: Int,
    val parentId: String?,
    val children: Int,
    /** Learned percent; for a locked level, progress towards opening it. */
    val ring: Int,
    val locked: Boolean,
    val level: String?,
    val newCount: Int,
    val learning: Int,
    val review: Int,
    /** owner, editor, viewer or read_only. */
    val role: String,
    /** Who lent it, on a shared deck at the top. */
    val from: String?,
    /** Notes in it and its sub-decks. */
    val notes: Int,
    /** Today's counts as a line ("3 new · 1 to review"), or what it waits for. */
    val detail: String,
)

internal fun Overview.item(d: Deck, depth: Int, access: Access, from: Map<String, String?> = emptyMap()): DeckItem {
    val level = levels[d.id]
    val c = counts(d.id)
    val levelName = level?.takeIf { it.of > 0 }?.let { strings.levelOf(it.index, it.of) }
    val role = access.role(d.id).name.lowercase()
    val notes = tree.subtree(d.id).sumOf { loads[it.id]?.notes ?: 0 }
    val locked = level?.open == false
    val before = tree.children(d.parentId ?: "").let { siblings -> siblings.getOrNull(siblings.indexOf(d) - 1) }
    val detail = when {
        locked && before != null -> strings.opensAfter(before.name)
        locked -> strings.locked
        notes == 0 -> strings.emptyDeck
        else -> strings.toStudy(c.total, notes)
    }
    return DeckItem(
        d.id, d.name, d.icon, d.color, depth, d.parentId, tree.children(d.id).size, level?.ring ?: 0,
        locked, levelName, c.new, c.learning, c.review, role, from[d.id], notes, detail,
    )
}

internal fun Overview.items(
    roots: List<Deck>,
    access: Access,
    from: Map<String, String?> = emptyMap(),
): Array<DeckItem> {
    val out = ArrayList<DeckItem>()
    fun walk(d: Deck, depth: Int) {
        out += item(d, depth, access, from)
        tree.children(d.id).forEach { walk(it, depth + 1) }
    }
    roots.forEach { walk(it, 0) }
    return out.toTypedArray()
}

@JsExport
class Removed internal constructor(val id: String, val deck: Boolean, val label: String, val deletedAt: Double)

@JsExport
class HomeState internal constructor(
    val mine: Array<DeckItem>,
    val shared: Array<DeckItem>,
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

/** Your decks and the ones shared with you, as trees, with today's plan above and Recently deleted below. */
@JsExport
class Home internal constructor(override val app: App) : Screen<HomeState>() {
    private val library get() = app.rondo.library
    private val store get() = app.rondo.store
    private var tree = Tree(emptyList())

    override suspend fun load(): HomeState {
        val o = library.overview()
        tree = o.tree
        val access = library.access()
        val (mine, shared) = o.tree.roots.partition { it.ownerId == store.me }
        val from = store.q.lends().awaitAsList().associate { it.deck_id to it.owner_name }
        // Recently deleted: decks that aren't inside another deleted one, and notes in decks still there.
        val decks = store.decks().filter { d ->
            d.deletedAt != null && d.ownerId == store.me && o.tree.ancestors(d.id).none { it.deletedAt != null }
        }
        val notes = store.q.deletedNotes(store.me, ::Notes).awaitAsList().map { it.model() }
        val deleted = decks.map { Removed(it.id, true, it.name, it.deletedAt!!.toDouble()) } +
            notes.filter { !o.tree.dead(it.deckId) }.map { Removed(it.id, false, label(it), it.deletedAt!!.toDouble()) }
        val activity = store.activity()
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
            o.items(mine, access),
            o.items(shared, access, from),
            deleted.sortedByDescending { it.deletedAt }.toTypedArray(),
            due, studied, activity.tomorrow, activity.streak, activity.days.takeLast(7).toTypedArray(),
            headline, summary,
            o.opened.mapNotNull { o.tree[it]?.name }.toTypedArray(),
        )
    }

    /** A new deck under [parentId] (null: the top level) with its look; [then] gets its id. */
    fun create(name: String, parentId: String?, icon: String?, color: Int, then: (String) -> Unit) = act {
        if (name.isBlank()) return@act
        val deck = library.createDeck(name, parentId)
        val look = icon?.ifBlank { null }
        if (look != null || color != 0) library.updateDeck(deck.id) { it.copy(icon = look, color = color) }
        then(deck.id)
    }

    fun rename(id: String, name: String) = act {
        if (name.isNotBlank()) library.updateDeck(id) { it.copy(name = name.trim()) }
    }

    /** Where [id] can move: the top level, or any of your decks outside it. */
    fun targets(id: String): Array<Choice> {
        val inside = tree.subtree(id).map { it.id }.toSet()
        val decks = tree.byId.values.filter { it.ownerId == store.me && it.id !in inside && !tree.dead(it.id) }
        return (listOf(Choice("", strings.topLevel)) + tree.choices(decks)).toTypedArray()
    }

    /**
     * Moves a deck under [parentId] (null: the top level), before [beforeId] or last. A move that
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
        if (deck.parentId == parent && (beforeId == id || beforeId == tree.after(id)?.id)) return@act
        library.move(id, parent, beforeId)
    }

    fun delete(id: String) = act { library.delete(id) }

    fun restore(id: String, deck: Boolean) = act { if (deck) library.restore(id) else library.restoreNote(id) }

    /** Copies a deck into your own; [then] gets the copy's id. */
    fun copy(id: String, then: (String) -> Unit) = act { then(library.copy(id)) }

    /** Stops borrowing a shared deck. */
    fun leave(id: String) = act { leave(app, id) }
}

/** Stops borrowing [id], a deck lent to you. */
internal suspend fun leave(app: App, id: String) {
    online { app.rondo.net.api.removeShare(id, app.rondo.store.me).ok() }
    app.rondo.syncNow()
}

/** The language [id] is in: its own, or the nearest deck above it that has one. */
internal fun Tree.language(id: String): String? =
    (listOfNotNull(get(id)) + ancestors(id)).firstNotNullOfOrNull { it.language }

/** The sibling right after [id], if any. */
private fun Tree.after(id: String): Deck? {
    val siblings = get(id)?.parentId?.let { children(it) } ?: roots
    return siblings.getOrNull(siblings.indexOfFirst { it.id == id } + 1)
}

/** A note as lists name it: its first field, as plain text. */
internal fun label(n: Note): String {
    val first = n.fields.entries.minByOrNull { it.key.toIntOrNull() ?: 0 }?.value.orEmpty()
    return Markup.plain(first).take(80).ifBlank { "…" }
}

@JsExport
class DeckState internal constructor(
    val deck: DeckItem,
    val path: Array<String>,
    val description: String?,
    val language: String?,
    val newPerDay: Int,
    val reviewsPerDay: Int,
    val retention: Int,
    val gated: Boolean,
    val children: Array<DeckItem>,
    /** Each sub-deck's requirement: the percent of the one before it (the first has none). */
    val unlockAt: Array<Int>,
    val notes: Int,
    val learned: Int,
    val canEdit: Boolean,
    val owned: Boolean,
    /** Lent to you, at the top of what you borrow: you can leave it. */
    val lent: Boolean,
    /** Its author removed it. */
    val frozen: Boolean,
    /** Some notes keep Anki's format: it can be converted. */
    val anki: Boolean,
    val gone: Boolean,
)

/** One deck: its header, sub-decks and levels, and the settings sheet. */
@JsExport
class DeckScreen internal constructor(override val app: App, val id: String) : Screen<DeckState>() {
    private val library get() = app.rondo.library
    private val store get() = app.rondo.store

    override suspend fun load(): DeckState {
        val o = library.overview()
        val access = library.access()
        val d = o.tree[id]
        if (d == null || o.tree.dead(id)) {
            val none = DeckItem(id, "", null, 0, 0, null, 0, 0, false, null, 0, 0, 0, "none", null, 0, "")
            return DeckState(
                none, emptyArray(), null, null, 0, 0, 90, false, emptyArray(), emptyArray(), 0, 0,
                false, false, false, false, false, true,
            )
        }
        val subtree = o.tree.subtree(id)
        val kids = o.tree.children(id)
        val role = access.role(id)
        val loads = subtree.mapNotNull { o.loads[it.id] }
        val anki = store.q.ankiNotes(subtree.map { it.id }).awaitAsOne() > 0
        return DeckState(
            o.item(d, 0, access), o.tree.path(id).toTypedArray(), d.description, d.language, d.newPerDay,
            d.reviewsPerDay, d.retention, d.gated, kids.map { o.item(it, 1, access) }.toTypedArray(),
            kids.map { it.unlockAt }.toTypedArray(), loads.sumOf { it.notes }, loads.sumOf { it.learnedNotes },
            access.canWrite(id) == null, role == Role.OWNER, id in o.lends, d.ownerId == null, anki, false,
        )
    }

    fun save(
        name: String,
        icon: String?,
        color: Int,
        description: String?,
        language: String?,
        newPerDay: Int,
        reviewsPerDay: Int,
        retention: Int,
        gated: Boolean,
    ) = act {
        library.updateDeck(id) {
            it.copy(
                name = name.trim(),
                icon = icon?.ifBlank { null },
                color = color,
                description = description?.trim()?.ifBlank { null },
                language = language?.ifBlank { null },
                newPerDay = newPerDay,
                reviewsPerDay = reviewsPerDay,
                retention = retention,
                gated = gated,
            )
        }
    }

    /** How much of the sub-deck before [childId] must be learned for it to open: 50 to 100, by tens. */
    fun setUnlockAt(childId: String, percent: Int) = act { library.updateDeck(childId) { it.copy(unlockAt = percent) } }

    /** Opens a locked level now, whatever the one before it says. */
    fun unlock(childId: String) = act { library.unlock(childId) }

    fun delete() = act { library.delete(id) }

    /** Copies the deck into your own; [then] gets the copy's id. */
    fun copy(then: (String) -> Unit) = act { then(library.copy(id)) }

    /** An editable copy of a deck kept in Anki's format; [then] gets its id. */
    fun convert(then: (String) -> Unit) = act { then(library.copy(id, convert = true)) }

    /** Stops borrowing this deck (lent to you); [then] runs once it's gone. */
    fun leave(then: () -> Unit) = act {
        leave(app, id)
        then()
    }
}

@JsExport
class Member internal constructor(val userId: String, val name: String?, val role: Int)

@JsExport
class InviteItem internal constructor(val id: String, val email: String?, val role: Int, val expires: String)

@JsExport
class ShareState internal constructor(
    val members: Array<Member>,
    val invites: Array<InviteItem>,
    /** A link just made, shown until the sheet closes. */
    val link: String?,
    val published: Boolean,
    val slug: String?,
    val followers: Int,
    val pro: Boolean,
    val offline: Boolean,
    /** Moderators took it off Discover; it can't be listed again. */
    val removed: Boolean,
    /** Until when sharing is restricted for you (ISO), or null. */
    val restrictedUntil: String?,
)

/** Who a deck is shared with, invitations, a link, and Discover. Needs a connection. */
@JsExport
class ShareScreen internal constructor(override val app: App, val deckId: String) : Screen<ShareState>() {
    override val live get() = false
    private val api get() = app.rondo.net.api
    private var link: String? = null

    override suspend fun load(): ShareState = try {
        online {
            val shares = api.listShares(deckId).ok()
            val invites = api.listInvites(deckId).ok()
            val publication = api.getPublication(deckId).takeIf { it.success }?.body()
            ShareState(
                members = shares.map { Member(it.userId, it.name, it.role) }.toTypedArray(),
                invites = invites.map { InviteItem(it.id, it.email, it.role, it.expiresAt.toString()) }.toTypedArray(),
                link = link,
                published = publication != null && publication.removed != true,
                slug = publication?.slug,
                followers = publication?.followers ?: 0,
                pro = app.rondo.account.me?.pro == true,
                offline = false,
                removed = publication?.removed == true,
                restrictedUntil = app.rondo.account.me?.restrictedUntil?.toString(),
            )
        }
    } catch (e: ApiError) {
        if (e.status != 0) app.notice(e)
        ShareState(emptyArray(), emptyArray(), null, false, null, 0, false, true, false, null)
    }

    private fun change(block: suspend () -> Unit) = act {
        online { block() }
        show(load())
    }

    fun invite(email: String, role: Int) = change { api.createInvite(deckId, InviteRequest(role, email.trim())).ok() }

    fun createLink(role: Int) = change { link = api.createInvite(deckId, InviteRequest(role)).ok().url }

    fun revoke(inviteId: String) = change { api.revokeInvite(inviteId).ok() }

    fun setRole(userId: String, role: Int) = change { api.setShareRole(deckId, userId, RoleUpdate(role)).ok() }

    fun remove(userId: String) = change { api.removeShare(deckId, userId).ok() }

    fun publish() = change { api.publishDeck(deckId).ok() }

    fun unpublish() = change { api.unpublishDeck(deckId).ok() }
}

@JsExport
class Issue internal constructor(
    val id: String,
    val entity: String,
    val label: String,
    val reason: String,
    val at: Double,
    val canCopy: Boolean,
)

@JsExport
class IssuesState internal constructor(val items: Array<Issue>)

/** Edits the server refused: save your version as a copy, or let it go. */
@JsExport
class IssuesScreen internal constructor(override val app: App) : Screen<IssuesState>() {
    private val store get() = app.rondo.store

    override suspend fun load() = IssuesState(
        store.q.rejects().awaitAsList().map {
            val canCopy = it.code != "quota_exceeded" && it.code != "too_large"
            Issue(it.id, it.entity, it.label, strings.error(it.code), it.at.toDouble(), canCopy)
        }.toTypedArray(),
    )

    fun discard(id: String, entity: String) = act { store.q.dropReject(id, entity) }

    /** Your refused version as something of your own: a note in a deck you own, or a new deck. */
    fun keepCopy(id: String, entity: String) = act {
        val reject = store.q.rejects().awaitAsList().firstOrNull { it.id == id && it.entity == entity } ?: return@act
        val library = app.rondo.library
        when (entity) {
            "note" -> {
                val mine = json.decodeFromString<Note>(reject.body)
                val access = library.access()
                val deck = mine.deckId.takeIf { access.canWrite(it) == null && store.tree()[it]?.ownerId == store.me }
                    ?: store.tree().roots.firstOrNull { it.ownerId == store.me && it.name == strings.recovered }?.id
                    ?: library.createDeck(strings.recovered).id
                var template = store.template(mine.templateId) ?: throw Refused(ErrorCode.NOT_FOUND)
                // Someone else's note type comes along as a copy of your own.
                if (!Templates.isBuiltin(template.id) && template.ownerId != store.me) {
                    template = library.saveTemplate(template.copy(id = Ids.new(), deletedAt = null))
                }
                library.newNote(deck, template.id, mine.fields)
            }

            "deck" -> {
                val mine = json.decodeFromString<Deck>(reject.body)
                val d = library.createDeck(mine.name)
                library.updateDeck(d.id) {
                    mine.copy(id = d.id, ownerId = d.ownerId, parentId = null, position = d.position, deletedAt = null)
                }
            }

            else -> Unit
        }
        store.q.dropReject(id, entity)
    }
}
