package app.rondo.core.notes.infrastructure

import app.rondo.core.notes.domain.Filter
import kotlin.uuid.Uuid

/** A filter as a link says it: `q`, `deck`, `type`, `tag`, `state` and `marked`, each when set. */
class FilterLink(val params: Map<String, List<String>>) {
    constructor(filter: Filter) : this(
        listOfNotNull(
            filter.text?.let { "q" to listOf(it) },
            filter.deckIds?.let { "deck" to it },
            filter.templateIds?.let { "type" to it },
            filter.tags?.let { "tag" to it },
            filter.state?.let { "state" to listOf(it) },
            ("marked" to listOf("1")).takeIf { filter.marked == true },
        ).toMap(),
    )

    /** The filter it says. What a filter can't hold is left out, so what a link opens is always valid. */
    val filter: Filter get() = Filter(
        deckIds = params["deck"]?.filter { Uuid.parseOrNull(it) != null }?.ifEmpty { null },
        templateIds = params["type"]?.filter { Uuid.parseOrNull(it) != null }?.ifEmpty { null },
    ).withText(params["q"]?.firstOrNull().orEmpty())
        .withTags(params["tag"].orEmpty())
        .withState(params["state"]?.firstOrNull())
        .withMarked("marked" in params)
}
