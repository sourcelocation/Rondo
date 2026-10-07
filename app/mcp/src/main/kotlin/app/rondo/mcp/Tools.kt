package app.rondo.mcp

import app.rondo.core.Days
import app.rondo.core.DeckLoad
import app.rondo.core.Decks
import app.rondo.core.Fsrs
import app.rondo.core.Ids
import app.rondo.core.Learning
import app.rondo.core.Memory
import app.rondo.core.NoteFilter
import app.rondo.core.Positions
import app.rondo.core.Templates
import app.rondo.core.Tree
import app.rondo.core.Workspace
import app.rondo.core.model.Deck
import app.rondo.core.model.Note
import app.rondo.core.model.Rows
import app.rondo.core.model.Template
import app.rondo.core.nowMillis
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val INSTRUCTIONS = """Rondo is a flashcard app. Decks nest; notes live in decks and use a template
whose fields they fill; each note makes one or more cards. Field text is Rondo markup: **bold**, *italic*, __underline__,
==highlight==, `code`, ~sub~, ^sup^, ${'$'}inline math${'$'}, ${'$'}${'$'} display math ${'$'}${'$'} on its own lines,
{{c1::cloze answer::hint}}, {base|ruby reading}, lines starting with "- " or "1. " for lists, pipe tables.
Escape a special character with a backslash. Image and audio fields hold a media hash."""

/** The MCP server for one [Workspace]: the same tools hosted and on the command line. */
fun rondoServer(ws: Workspace): Server = Server(
    Implementation("rondo", "1"),
    ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools())),
    INSTRUCTIONS,
) { tools(ws) }

private fun schema(vararg props: Pair<String, JsonObject>, required: List<String> = emptyList()) =
    ToolSchema(properties = JsonObject(props.toMap()), required = required)

private fun prop(type: String, description: String) = buildJsonObject {
    put("type", type)
    put("description", description)
}

private fun array(description: String, items: JsonObject = buildJsonObject { put("type", "string") }) =
    buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", items)
    }

private fun ok(json: JsonElement) = CallToolResult(listOf(TextContent(json.toString())))

private fun refused(message: String) = CallToolResult(listOf(TextContent(message)), isError = true)

private fun JsonObject?.str(key: String) = this?.get(key)?.jsonPrimitive?.content

private fun JsonObject?.int(key: String) = this?.get(key)?.jsonPrimitive?.intOrNull

private fun JsonObject?.list(key: String) = this?.get(key)?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

private fun Server.tools(ws: Workspace) {
    addTool(
        "list_decks",
        "Every deck you own or can use, with its path, your role and how many notes it has.",
        schema(),
    ) {
        val decks = ws.decks()
        ok(
            buildJsonArray {
                for (d in decks) {
                    addJsonObject {
                        put("id", d.deck.id)
                        put("path", d.path.joinToString(" / "))
                        put("role", d.role.name.lowercase())
                        put("notes", d.notes)
                    }
                }
            },
        )
    }

    addTool(
        "list_templates",
        "Note templates: their fields (fill these when creating notes) and the cards they make.",
        schema(),
    ) {
        ok(buildJsonArray { for (t in ws.templates()) add(describe(t)) })
    }

    addTool(
        "search_notes",
        "Find notes by text and deck (including its sub-decks).",
        schema(
            "text" to prop("string", "Words the note contains."),
            "deck_id" to prop("string", "Look only in this deck."),
            "limit" to prop("integer", "At most this many (default 20, up to 200)."),
        ),
    ) { call ->
        val a = call.arguments
        val tree = Tree(ws.decks().map { it.deck })
        val decks = a.str("deck_id")?.let { id -> tree.subtree(id).map { it.id } }.orEmpty()
        val notes = ws.notes(
            NoteFilter(a.str("text").orEmpty(), decks),
            (a.int("limit") ?: 20).coerceIn(1, 200),
        )
        ok(notes(ws, notes, tree))
    }

    addTool("get_notes", "Notes by id.", schema("ids" to array("Note ids."), required = listOf("ids"))) { call ->
        ok(notes(ws, ws.notes(call.arguments.list("ids")), Tree(ws.decks().map { it.deck })))
    }

    addTool(
        "create_deck",
        "Creates a deck, or a path of decks like \"Spanish/Verbs\" (missing parts are made). Returns its id.",
        schema(
            "path" to prop("string", "Deck names separated by /."),
            "parent_id" to prop("string", "Create the path under this deck."),
            required = listOf("path"),
        ),
    ) { call ->
        var parent = call.arguments.str("parent_id")
        val made = ArrayList<Deck>()
        val tree = Tree(ws.decks().map { it.deck })
        val names = call.arguments.str("path").orEmpty().split('/').map { it.trim() }.filter { it.isNotEmpty() }
        for (name in names) {
            val existing = if (parent == null) tree.roots.filter { it.ownerId == ws.actor } else tree.children(parent)
            val siblings = existing + made.filter { it.parentId == parent }
            val found = siblings.firstOrNull { it.name.equals(name, ignoreCase = true) }
            if (found != null) {
                parent = found.id
                continue
            }
            val owner = if (parent == null) ws.actor else (tree[parent] ?: made.first { it.id == parent }).ownerId
            val position = Positions.between(siblings.maxOfOrNull { it.position }, null)
            val deck = Decks.new(name, position, ws.clock(), owner, parent)
            made += deck
            parent = deck.id
        }
        val rejected = ws.commit(Rows(decks = made))
        if (rejected.isNotEmpty()) return@addTool refused("Refused: ${rejected.first().code.value}")
        ok(buildJsonObject { put("id", parent) })
    }

    addTool(
        "update_deck",
        "Renames a deck or changes its description, daily limits or whether its sub-decks unlock in order.",
        schema(
            "id" to prop("string", "The deck."),
            "name" to prop("string", "New name."),
            "description" to prop("string", "New description."),
            "new_per_day" to prop("integer", "New cards a day."),
            "reviews_per_day" to prop("integer", "Reviews a day."),
            "gated" to prop("boolean", "Unlock sub-decks in order."),
            required = listOf("id"),
        ),
    ) { call ->
        val a = call.arguments
        val deck = ws.decks().firstOrNull { it.deck.id == a.str("id") }?.deck ?: return@addTool refused("No such deck.")
        val changed = deck.copy(
            name = a.str("name") ?: deck.name,
            description = a.str("description") ?: deck.description,
            v = ws.clock(),
            newPerDay = a.int("new_per_day") ?: deck.newPerDay,
            reviewsPerDay = a.int("reviews_per_day") ?: deck.reviewsPerDay,
            gated = a?.get("gated")?.jsonPrimitive?.booleanOrNull ?: deck.gated,
        )
        val rejected = ws.commit(Rows(decks = listOf(changed)))
        if (rejected.isNotEmpty()) refused("Refused: ${rejected.first().code.value}") else ok(JsonPrimitive("updated"))
    }

    val noteItem = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("fields") {
                put("type", "object")
                put("description", "Field name (or id) to markup.")
            }
        }
    }
    addTool(
        "create_notes",
        "Adds notes to a deck with a template. Each note's fields are keyed by field name.",
        schema(
            "deck_id" to prop("string", "The deck."),
            "template_id" to prop("string", "A template from list_templates."),
            "notes" to array("The notes.", noteItem),
            required = listOf("deck_id", "template_id", "notes"),
        ),
    ) { call ->
        val a = call.arguments
        val template = ws.templates().firstOrNull { it.id == a.str("template_id") }
            ?: return@addTool refused("No such template.")
        val deck = ws.decks().firstOrNull { it.deck.id == a.str("deck_id") }?.deck
            ?: return@addTool refused("No such deck.")
        val notes = a?.get("notes")?.jsonArray.orEmpty().map { it.jsonObject }.map { n ->
            Note(
                Ids.new(),
                deck.id,
                template.id,
                fields(template, n["fields"]?.jsonObject),
                ws.clock(),
                deck.ownerId,
            )
        }
        val rejected = ws.commit(Rows(notes = notes))
        ok(
            buildJsonObject {
                putJsonArray("created") { notes.filter { n -> rejected.none { it.id == n.id } }.forEach { add(it.id) } }
                putJsonArray("refused") { rejected.forEach { add(it.code.value) } }
            },
        )
    }

    addTool(
        "update_notes",
        "Changes notes' fields (by name).",
        schema(
            "notes" to array("Notes with their id and the fields to change.", noteItem),
            required = listOf("notes"),
        ),
    ) { call ->
        val changes = call.arguments?.get("notes")?.jsonArray.orEmpty().map { it.jsonObject }
        val stored = ws.notes(changes.mapNotNull { it.str("id") }).associateBy { it.id }
        val templates = ws.templates().associateBy { it.id }
        val notes = changes.mapNotNull { c ->
            val n = stored[c.str("id")] ?: return@mapNotNull null
            val template = templates.getValue(n.templateId)
            val fields = c["fields"]?.jsonObject?.let { n.fields + fields(template, it) } ?: n.fields
            n.copy(fields = fields, v = ws.clock())
        }
        val rejected = ws.commit(Rows(notes = notes))
        ok(buildJsonObject { put("updated", notes.size - rejected.size) })
    }

    addTool(
        "move_notes",
        "Moves notes to another deck of the same owner.",
        schema(
            "ids" to array("Note ids."),
            "deck_id" to prop("string", "Target deck."),
            required = listOf("ids", "deck_id"),
        ),
    ) { call ->
        val target = call.arguments.str("deck_id")!!
        val notes = ws.notes(call.arguments.list("ids")).map { it.copy(deckId = target, v = ws.clock()) }
        val rejected = ws.commit(Rows(notes = notes))
        if (rejected.isNotEmpty()) refused("Refused: ${rejected.first().code.value}") else ok(JsonPrimitive("moved"))
    }

    addTool(
        "delete_notes",
        "Deletes notes (they stay restorable for 30 days).",
        schema("ids" to array("Note ids."), required = listOf("ids")),
    ) { call ->
        val notes = ws.notes(call.arguments.list("ids")).map { it.copy(deletedAt = nowMillis(), v = ws.clock()) }
        val rejected = ws.commit(Rows(notes = notes))
        if (rejected.isNotEmpty()) refused("Refused: ${rejected.first().code.value}") else ok(JsonPrimitive("deleted"))
    }

    addTool(
        "due_today",
        "How many new, learning and review cards a deck (or everything) offers today.",
        schema("deck_id" to prop("string", "The deck; all decks when left out.")),
    ) { call ->
        ok(due(ws, call.arguments.str("deck_id")))
    }
}

private fun describe(t: Template) = buildJsonObject {
    put("id", t.id)
    put("name", t.name)
    put("builtin", Templates.isBuiltin(t.id))
    putJsonArray("fields") {
        for (f in t.def.fields) {
            addJsonObject {
                put("id", f.id)
                put("name", f.name)
                put("kind", f.kind.value)
            }
        }
    }
    putJsonArray("cards") { t.def.cards.forEach { add(it.name) } }
}

/** Field values keyed by field name or id, as stored (by id). */
private fun fields(t: Template, given: JsonObject?): Map<String, String> = given.orEmpty().mapNotNull { (key, value) ->
    val field = t.def.fields.firstOrNull { it.name.equals(key, ignoreCase = true) || it.id.toString() == key }
    field?.let { it.id.toString() to value.jsonPrimitive.content }
}.toMap()

private suspend fun notes(ws: Workspace, notes: List<Note>, tree: Tree) = buildJsonArray {
    val templates = ws.templates().associateBy { it.id }
    for (n in notes) {
        val t = templates[n.templateId]
        addJsonObject {
            put("id", n.id)
            put("deck", tree.path(n.deckId).joinToString(" / "))
            put("template", t?.name ?: n.templateId)
            putJsonObject("fields") {
                for ((id, value) in n.fields) {
                    val name = t?.def?.fields?.firstOrNull { it.id.toString() == id }?.name
                    put(name ?: id, value)
                }
            }
        }
    }
}

private fun Boolean.toInt() = if (this) 1 else 0

/** Today's counts, folded from events exactly as devices do. */
private suspend fun due(ws: Workspace, deck: String?): JsonObject {
    val now = nowMillis()
    val days = Days(ws.settingsTimezone())
    val today = days.start(days.index(now))
    val tree = Tree(ws.decks().map { it.deck })
    val roots = deck?.let(::listOf) ?: tree.roots.map { it.id }
    val decks = roots.flatMap { tree.subtree(it) }
    val notes = ws.notes(NoteFilter(decks = decks.map { it.id }), 100_000)
    val templates = ws.templates().associateBy { it.id }
    val events = ws.events(notes.map { it.id } + decks.map { it.id }).groupBy { it.subjectId }
    val fsrs = Fsrs()
    val loads = HashMap<String, DeckLoad>()
    for (n in notes) {
        val t = templates[n.templateId] ?: continue
        val byCard = events[n.id].orEmpty().groupBy { it.card }
        var learned = false
        for (card in Templates.cards(t, n)) {
            val s = Learning.fold("${n.id}:$card", byCard[card].orEmpty(), fsrs)
            learned = learned || s.learnedAt != null
            if (s.suspended) continue
            val m = s.memory
            val due = m.due ?: 0
            val l = loads[n.deckId] ?: DeckLoad()
            loads[n.deckId] = l.copy(
                newAvailable = l.newAvailable + (m.state == Memory.NEW).toInt(),
                learningDue = l.learningDue + (m.state != Memory.NEW && m.state != Memory.REVIEW && due <= now).toInt(),
                reviewDue = l.reviewDue + (m.state == Memory.REVIEW && due < today + Days.DAY).toInt(),
                newToday = l.newToday + ((s.introducedAt ?: 0) >= today).toInt(),
            )
        }
        val l = loads[n.deckId] ?: DeckLoad()
        loads[n.deckId] = l.copy(notes = l.notes + 1, learnedNotes = l.learnedNotes + learned.toInt())
    }
    val unlocked = events.values.flatten().filter { it.kind == Learning.UNLOCK }.map { it.subjectId }.toSet()
    val levels = Learning.levels(tree, { loads[it] ?: DeckLoad() }, unlocked)
    return buildJsonObject {
        for (root in roots) {
            putJsonObject(tree[root]?.name ?: root) {
                val c = Learning.counts(tree, root, { loads[it] ?: DeckLoad() }, levels)
                put("new", c.new)
                put("learning", c.learning)
                put("review", c.review)
            }
        }
    }
}
