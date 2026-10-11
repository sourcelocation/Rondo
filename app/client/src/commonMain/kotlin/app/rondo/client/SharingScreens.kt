package app.rondo.client

import app.rondo.client.net.ApiError
import app.rondo.client.services.DiscoverItem
import app.rondo.client.services.PublicDeck
import app.rondo.core.ErrorCode
import app.rondo.core.Refused
import app.rondo.core.Tree
import kotlin.js.JsExport

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
    private val sharing get() = app.rondo.sharing
    private var link: String? = null

    override suspend fun load(): ShareState = try {
        val shares = sharing.shares(deckId)
        val invites = sharing.invites(deckId)
        val publication = sharing.publication(deckId)
        ShareState(
            members = shares.map { Member(it.userId, it.name, it.role) }.toTypedArray(),
            invites = invites.map { InviteItem(it.id, it.email, it.role, it.expiresAt) }.toTypedArray(),
            link = link,
            published = publication != null && publication.removed != true,
            slug = publication?.slug,
            followers = publication?.followers ?: 0,
            pro = app.rondo.account.me?.pro == true,
            offline = false,
            removed = publication?.removed == true,
            restrictedUntil = app.rondo.account.me?.restrictedUntil,
        )
    } catch (e: ApiError) {
        if (e.status != 0) app.notice(e)
        ShareState(emptyArray(), emptyArray(), null, false, null, 0, false, true, false, null)
    }

    private fun change(block: suspend () -> Unit) = act {
        block()
        show(load())
    }

    fun invite(email: String, role: Int) = change { sharing.invite(deckId, role, email.trim()) }

    fun createLink(role: Int) = change { link = sharing.invite(deckId, role, null).url }

    fun revoke(inviteId: String) = change { sharing.revoke(inviteId) }

    fun setRole(userId: String, role: Int) = change { sharing.setRole(deckId, userId, role) }

    fun remove(userId: String) = change { sharing.remove(deckId, userId) }

    fun publish() = change { sharing.publish(deckId) }

    fun unpublish() = change { sharing.unpublish(deckId) }
}

@JsExport
class PublicItem internal constructor(
    /** Null when it isn't on Discover (seen through an invitation). */
    val slug: String?,
    val deckId: String,
    val name: String,
    val description: String?,
    val language: String?,
    val icon: String?,
    val color: Int,
    val owner: String?,
    /** The author's username, for their profile, and flag. */
    val ownerUsername: String?,
    val ownerFlag: String?,
    val followers: Int,
    val notes: Int,
    /** Already in your decks. */
    val following: Boolean,
) {
    internal constructor(d: DiscoverItem, following: Boolean) : this(
        d.slug, d.deckId, d.name, d.description, d.language, d.icon, d.color ?: 0, d.ownerName, d.ownerUsername,
        d.ownerFlag, d.followers, d.notes, following,
    )
}

@JsExport
class DiscoverState internal constructor(
    val items: Array<PublicItem>,
    val more: Boolean,
    val query: String,
    val language: String?,
    /** top (most followed), new or hot (most followed this week). */
    val sort: String,
    val loading: Boolean,
    val offline: Boolean,
)

/** Public decks, by name and language: the most followed, the newest, or what's hot this week. */
@JsExport
class DiscoverScreen internal constructor(override val app: App) : Screen<DiscoverState>() {
    override val live get() = false
    private var query = ""
    private var language: String? = null
    private var sort = "top"
    private var page = 0
    private val items = ArrayList<DiscoverItem>()
    private var more = false

    override suspend fun load(): DiscoverState {
        val offline = try {
            if (page == 0) items.clear()
            val found = app.rondo.discover.search(query.ifBlank { null }, language, page, sort)
            items += found.items
            more = found.more
            false
        } catch (e: ApiError) {
            if (e.status != 0) throw e
            true
        }
        val tree = app.rondo.library.tree()
        val shown = items.map { PublicItem(it, tree[it.deckId] != null) }
        return DiscoverState(shown.toTypedArray(), more, query, language, sort, false, offline)
    }

    fun search(query: String, language: String?) = act {
        this.query = query
        this.language = language?.ifBlank { null }
        page = 0
        show(load())
    }

    /** Orders by [sort]: top, new or hot. */
    fun sortBy(sort: String) = act {
        this.sort = sort.takeIf { it in SORTS } ?: return@act
        page = 0
        show(load())
    }

    fun more() = act {
        page++
        show(load())
    }

    private companion object {
        val SORTS = setOf("top", "new", "hot")
    }
}

/**
 * A deck seen before you have it: from Discover or a share link ([slug]) or an invitation ([token]).
 * [role]: what the invitation gives (1 viewer, 2 editor), or 0 from Discover. [opening]: the deck
 * that's yours to open now; after Follow, Accept or Copy it's set once the deck has synced.
 */
@JsExport
class PreviewState internal constructor(
    val item: PublicItem?,
    val decks: Array<String>,
    val samples: Array<Sample>,
    val signedIn: Boolean,
    val role: Int,
    val busy: Boolean,
    val opening: String?,
)

/** One page for every way to look at a deck before taking it: Discover, a share link, an invitation. */
@JsExport
class PreviewScreen internal constructor(
    override val app: App,
    private val slug: String?,
    private val token: String?,
) : Screen<PreviewState>() {
    override val live get() = false
    private var page: PublicDeck? = null
    private var role = 0
    private var fetched = false
    private var busy = false
    private var opening: String? = null

    override suspend fun load(): PreviewState {
        if (!fetched) {
            fetched = true
            if (token != null) {
                app.rondo.sharing.invitation(token)?.let {
                    page = it.deck
                    role = it.role
                }
            } else if (slug != null) {
                page = app.rondo.discover.deck(slug)
            }
        }
        val signedIn = app.rondo.account.signedIn
        val d = page ?: return PreviewState(null, emptyArray(), emptyArray(), signedIn, role, busy, opening)
        val tree = app.rondo.library.tree()
        val sub = Tree(d.decks)
        val samples = d.samples.mapNotNull { samples(it.template, it.note).firstOrNull() }
        val paths = sub.byId.values.map { sub.path(it.id).joinToString(" › ") }.sorted()
        return PreviewState(
            PublicItem(d.item, tree[d.item.deckId] != null),
            paths.toTypedArray(),
            samples.toTypedArray(),
            signedIn,
            role,
            busy,
            opening,
        )
    }

    /** Runs [block], which gets a deck of yours, and opens it once it's on this device. */
    private fun take(block: suspend () -> String) = act {
        busy = true
        show(load())
        try {
            opening = block().takeIf { app.rondo.library.tree()[it] != null }
        } finally {
            busy = false
            show(load())
        }
    }

    /** Syncs until the borrowed deck [id] has arrived (a few tries). */
    private suspend fun arrive(id: String): String {
        repeat(5) { if (app.rondo.library.tree()[id] == null) app.rondo.syncNow() }
        return id
    }

    private suspend fun followed(): String {
        val id = page?.item?.deckId ?: throw Refused(ErrorCode.NOT_FOUND)
        if (app.rondo.library.tree()[id] != null) return id
        return arrive(app.rondo.discover.follow(slug ?: throw Refused(ErrorCode.NOT_FOUND)))
    }

    /** Follows a deck on Discover: it's yours to study, and the author's changes keep coming. */
    fun follow() = take { followed() }

    /** Joins the deck an invitation is for. */
    fun accept() = take {
        val invitation = token ?: throw Refused(ErrorCode.NOT_FOUND)
        arrive(app.rondo.sharing.accept(invitation))
    }

    /**
     * Staff: takes the deck off Discover for [reason] with a [note] its author sees; [takeDown] also
     * stops sharing it with the people who follow it.
     */
    fun removeFromDiscover(reason: String, note: String?, takeDown: Boolean) = act {
        val id = page?.item?.deckId ?: return@act
        val data = mapOf("take_down" to takeDown.toString())
        app.rondo.staff.act("deck.remove", deckId = id, reason = reason, note = note, data = data)
        app.say(strings.done)
    }

    /** A copy of your own to change: borrowed just long enough to copy it, unless you already follow it. */
    fun copy() = take {
        val had = page?.item?.deckId?.let { app.rondo.library.tree()[it] } != null
        val id = followed()
        app.rondo.library.copy(id).also { if (!had) app.leave(id) }
    }
}
