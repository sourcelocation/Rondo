package app.rondo.client.services

import app.rondo.client.net.ApiError
import app.rondo.client.net.Net
import app.rondo.core.decks.domain.Deck
import app.rondo.core.decks.infrastructure.DeckRecord
import app.rondo.core.files.domain.Contents
import app.rondo.core.notes.domain.Template
import app.rondo.core.notes.infrastructure.NoteRecord
import app.rondo.core.notes.infrastructure.TemplateRecord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What's public: decks on Discover, people's profiles, and reporting either. */
class Discover(private val net: Net) {
    /** Public decks matching [query] in [language], a [page] at a time, by [sort]: top, new or hot. */
    suspend fun search(query: String?, language: String?, page: Int, sort: String): DiscoverPage =
        net.get("/api/discover", "q" to query, "language" to language, "page" to page, "sort" to sort).read()

    /** A deck on Discover or behind a share link, before it's yours; null when there's none. */
    suspend fun deck(slug: String): PublicDeck? =
        absent { net.get("/api/discover/$slug").read<PublicDeckMessage>().deck() }

    /** Follows a deck on Discover: it's lent to you, and the author's changes keep coming. Its id. */
    suspend fun follow(slug: String): String = net.post("/api/discover/$slug/follow").read<Lent>().deckId

    /** Someone's public profile, with a [year] of their activity or the latest; null when there's no one. */
    suspend fun profile(username: String, year: Int?): PublicProfile? =
        absent { net.get("/api/users/$username", "year" to year).read<PublicProfile>() }

    /**
     * Reports a deck or a person ([kind] deck or person, [id]) for [reason]. Illegal content needs
     * the [law], a [note] saying why, and [goodFaith].
     */
    suspend fun report(kind: String, id: String, reason: String, law: String?, note: String?, goodFaith: Boolean) {
        net.post("/api/reports", ReportRequest(kind, id, reason, law, note?.ifBlank { null }, goodFaith))
    }

    private suspend fun <T> absent(block: suspend () -> T): T? = try {
        block()
    } catch (e: ApiError) {
        if (e.status != 404) throw e
        null
    }

    @Serializable
    private class ReportRequest(
        @SerialName("target_kind") val targetKind: String,
        @SerialName("target_id") val targetId: String,
        val reason: String,
        val law: String?,
        val note: String?,
        @SerialName("good_faith") val goodFaith: Boolean,
    )
}

/** A deck lent to you: its id. */
@Serializable
internal class Lent(@SerialName("deck_id") val deckId: String)

/** A deck as Discover lists it. */
@Serializable
class DiscoverItem(
    val slug: String? = null,
    @SerialName("deck_id") val deckId: String,
    val name: String,
    val description: String? = null,
    val language: String? = null,
    val icon: String? = null,
    val color: Int? = null,
    @SerialName("owner_name") val ownerName: String? = null,
    @SerialName("owner_username") val ownerUsername: String? = null,
    @SerialName("owner_flag") val ownerFlag: String? = null,
    val followers: Int = 0,
    val notes: Int = 0,
)

@Serializable
class DiscoverPage(val items: List<DiscoverItem>, val more: Boolean)

/** A deck before it's yours: how Discover lists it, its decks, and some of its notes. */
class PublicDeck(val item: DiscoverItem, val decks: List<Deck>, val samples: List<Contents.Entry>)

/** A [PublicDeck] as the server sends it: its rows as they're stored. */
@Serializable
internal class PublicDeckMessage(val item: DiscoverItem, val decks: List<DeckRecord>, val samples: Samples) {
    @Serializable
    class Samples(val templates: List<TemplateRecord>? = null, val notes: List<NoteRecord>? = null)

    fun deck(): PublicDeck {
        val templates =
            Template.builtins + samples.templates.orEmpty().mapNotNull { it.template() }.associateBy { it.id }
        val notes = samples.notes.orEmpty().map { it.note() }
        return PublicDeck(
            item,
            decks.map {
                it.deck()
            },
            notes.mapNotNull { n -> templates[n.templateId]?.let { Contents.Entry(n, it) } },
        )
    }
}

/** Someone's public page: who they are, their decks on Discover and their activity. [joined] is ISO. */
@Serializable
class PublicProfile(
    val id: String,
    val username: String,
    val name: String? = null,
    val flag: String? = null,
    val joined: String,
    val pro: Boolean = false,
    @SerialName("moved_from") val movedFrom: String? = null,
    val decks: List<DiscoverItem> = emptyList(),
    val activity: Activity? = null,
) {
    /** Reviews: all time, in [year], and in streaks of days. */
    @Serializable
    class Activity(
        val year: Int,
        val years: List<Int>,
        val reviews: Int,
        val learned: Int? = null,
        @SerialName("year_reviews") val yearReviews: Int,
        @SerialName("year_days") val yearDays: Int,
        @SerialName("year_minutes") val yearMinutes: Int,
        val streak: Int,
        @SerialName("longest_streak") val longestStreak: Int,
        val days: List<Day>,
    )

    /** One day's reviews; [date] ISO. */
    @Serializable
    class Day(val date: String, val reviews: Int)
}
