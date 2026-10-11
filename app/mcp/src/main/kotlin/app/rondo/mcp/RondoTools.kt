package app.rondo.mcp

import app.rondo.core.AnkiTemplate
import app.rondo.core.Days
import app.rondo.core.Deck
import app.rondo.core.DeckLoad
import app.rondo.core.ErrorCode
import app.rondo.core.Event
import app.rondo.core.Fsrs
import app.rondo.core.History
import app.rondo.core.Memory
import app.rondo.core.Note
import app.rondo.core.NoteFilter
import app.rondo.core.Position
import app.rondo.core.RondoTemplate
import app.rondo.core.Tags
import app.rondo.core.Template
import app.rondo.core.Tree
import app.rondo.core.Workspace
import app.rondo.core.file.Spreadsheet
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.io.File
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonArray
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

/**
 * The MCP tools for one [Workspace]: the same tools hosted and on the command line. [local]: they
 * run on the agent's own computer, so create_notes can read a file there. [html] turns a cell of
 * HTML (Anki's text export) into markup, where there's a way to; without, such cells are refused.
 */
class RondoTools(
    private val ws: Workspace,
    private val local: Boolean = false,
    private val html: ((String) -> String)? = null,
) {
    val server: Server by lazy {
        Server(
            Implementation("rondo", "1"),
            ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools())),
            INSTRUCTIONS,
        ) { tools() }
    }

    private fun schema(vararg props: Pair<String, JsonObject>?, required: List<String> = emptyList()) =
        ToolSchema(properties = JsonObject(props.filterNotNull().toMap()), required = required)

    private fun prop(type: String, description: String? = null) = buildJsonObject {
        put("type", type)
        description?.let { put("description", it) }
    }

    private val text = prop("string")

    private fun array(description: String? = null, items: JsonObject = text) = buildJsonObject {
        put("type", "array")
        description?.let { put("description", it) }
        put("items", items)
    }

    /** Field name to markup. */
    private val fieldValues = buildJsonObject {
        put("type", "object")
        put("additionalProperties", text)
    }

    private fun ok(text: String) = CallToolResult(listOf(TextContent(text)))

    private fun ok(json: JsonElement) = ok(json.toString())

    private fun refused(message: String) = CallToolResult(listOf(TextContent(message)), isError = true)

    /**
     * A write to notes in one line: how many it did, then what it didn't and why, a few named each
     * ("Moved 3 notes. Not found: a1b2…"). Nothing done and something wrong is an error.
     */
    private fun report(verb: String, count: Int, vararg not: Pair<String, List<String>>): CallToolResult {
        val rest = not.filter { it.second.isNotEmpty() }.map { (label, items) ->
            val more = if (items.size > NAMED) ", and ${items.size - NAMED} more" else ""
            "$label (${items.size}): ${items.take(NAMED).joinToString(", ")}$more."
        }
        val text = (listOf("$verb $count note${if (count == 1) "" else "s"}.") + rest).joinToString(" ")
        return CallToolResult(listOf(TextContent(text)), isError = count == 0 && rest.isNotEmpty())
    }

    private fun Map<String, ErrorCode>.named(
        label: (String) -> String = {
            it
        },
    ) = map { (id, code) -> "${label(id)} (${code.value})" }

    /** Rows under one header, each column named once. */
    private fun table(columns: List<String>, rows: List<List<JsonElement>>) = buildJsonObject {
        putJsonArray("columns") { columns.forEach { add(it) } }
        putJsonArray("rows") { rows.forEach { add(JsonArray(it)) } }
    }

    private fun JsonObject?.str(key: String) = this?.get(key)?.jsonPrimitive?.content

    private fun JsonObject?.int(key: String) = this?.get(key)?.jsonPrimitive?.intOrNull

    private fun JsonObject?.list(key: String) = this?.get(key)?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    /** [t]'s field called [key]: by name (case aside) or id. */
    private fun Template.field(key: String) =
        fields.firstOrNull { it.name.equals(key, ignoreCase = true) || it.id.toString() == key }

    private fun Server.tools() {
        addTool("list_decks", "With your role and how many notes each has.", schema()) {
            val decks = ws.decks()
            ok(
                table(
                    listOf("id", "path", "role", "notes"),
                    decks.map { d ->
                        listOf(
                            JsonPrimitive(d.deck.id),
                            JsonPrimitive(d.path.joinToString(" / ")),
                            JsonPrimitive(d.role.name.lowercase()),
                            JsonPrimitive(d.notes),
                        )
                    },
                ),
            )
        }

        addTool("list_templates", "Their fields, in order, and the cards each makes.", schema()) {
            ok(buildJsonArray { for (t in ws.templates()) add(describe(t)) })
        }

        val fieldsShown = "fields" to array("Only these fields; all when left out.")
        addTool(
            "search_notes",
            "Oldest first. When there are more, it returns `next`: pass it as `after`.",
            schema(
                "text" to prop("string", "Words the note contains."),
                "deck_id" to prop("string", "Includes its sub-decks."),
                "tags" to array("Any of these."),
                fieldsShown,
                "limit" to prop("integer", "Default 50, at most $PAGE."),
                "after" to text,
            ),
        ) { call ->
            val a = call.arguments
            val tree = Tree(ws.decks().map { it.deck })
            val deck = a.str("deck_id")
            val decks = deck?.let { id -> tree.subtree(id).map { it.id } }.orEmpty()
            if (deck != null && decks.isEmpty()) return@addTool refused("No such deck.")
            val limit = (a.int("limit") ?: 50).coerceIn(1, PAGE)
            val found = ws.notes(
                NoteFilter(a.str("text").orEmpty(), decks, tags = a.list("tags"), after = a.str("after")),
                limit + 1,
            )
            val page = found.take(limit)
            ok(notes(page, tree, a.list("fields"), page.lastOrNull()?.id?.takeIf { found.size > limit }))
        }

        addTool("get_notes", "By id.", schema("ids" to array(), fieldsShown, required = listOf("ids"))) { call ->
            val a = call.arguments
            ok(notes(ws.notes(a.list("ids")), Tree(ws.decks().map { it.deck }), a.list("fields")))
        }

        addTool(
            "create_deck",
            "\"Spanish/Verbs\" makes the parts that are missing. Returns the last one's id.",
            schema(
                "path" to text,
                "parent_id" to prop("string", "Make the path under this deck."),
                required = listOf("path"),
            ),
        ) { call ->
            var parent = call.arguments.str("parent_id")
            val made = ArrayList<Deck>()
            val tree = Tree(ws.decks().map { it.deck })
            val names = call.arguments.str("path").orEmpty().split('/').map { it.trim() }.filter { it.isNotEmpty() }
            for (name in names) {
                val existing = if (parent ==
                    null
                ) {
                    tree.roots.filter { it.ownerId == ws.actor }
                } else {
                    tree.children(parent)
                }
                val siblings = existing + made.filter { it.parentId == parent }
                val found = siblings.firstOrNull { it.name.equals(name, ignoreCase = true) }
                if (found != null) {
                    parent = found.id
                    continue
                }
                val owner = if (parent == null) ws.actor else (tree[parent] ?: made.first { it.id == parent }).ownerId
                val position = Position(siblings.maxOfOrNull { it.position }, null).key
                val deck = Deck(name, position, ws.clock(), owner, parent)
                made += deck
                parent = deck.id
            }
            val rejected = ws.save(decks = made)
            if (rejected.isNotEmpty()) return@addTool refused("Refused: ${rejected.values.first().value}")
            ok(buildJsonObject { put("id", parent) })
        }

        addTool(
            "update_deck",
            "Changes only what's given.",
            schema(
                "id" to text,
                "name" to text,
                "description" to text,
                "new_per_day" to prop("integer"),
                "reviews_per_day" to prop("integer"),
                "gated" to prop("boolean", "Unlock sub-decks in order."),
                required = listOf("id"),
            ),
        ) { call ->
            val a = call.arguments
            val deck =
                ws.decks().firstOrNull { it.deck.id == a.str("id") }?.deck ?: return@addTool refused("No such deck.")
            val changed = deck.copy(
                name = a.str("name") ?: deck.name,
                description = a.str("description") ?: deck.description,
                v = ws.clock(),
                newPerDay = a.int("new_per_day") ?: deck.newPerDay,
                reviewsPerDay = a.int("reviews_per_day") ?: deck.reviewsPerDay,
                gated = a?.get("gated")?.jsonPrimitive?.booleanOrNull ?: deck.gated,
            )
            val rejected = ws.save(decks = listOf(changed))
            if (rejected.isNotEmpty()) refused("Refused: ${rejected.values.first().value}") else ok("Updated.")
        }

        addTool(
            "delete_deck",
            "With its sub-decks and notes; restorable for 30 days.",
            schema("id" to text, required = listOf("id")),
        ) { call ->
            val view = ws.decks().firstOrNull { it.deck.id == call.arguments.str("id") }
                ?: return@addTool refused("No such deck.")
            val rejected = ws.save(decks = listOf(view.deck.copy(deletedAt = now(), v = ws.clock())))
            if (rejected.isNotEmpty()) {
                refused("Refused: ${rejected.values.first().value}")
            } else {
                ok("Deleted ${view.path.joinToString(" / ")}.")
            }
        }

        addTool(
            "create_notes",
            "Notes whose first field is already in the deck's top-level deck are skipped, unless on_duplicate is " +
                "\"add\": sending a batch again is safe.",
            schema(
                "deck_id" to text,
                "template_id" to text,
                "columns" to array(
                    "Field names, and \"tags\" for each row's own. The template's fields when left out.",
                ),
                "rows" to array("Values in column order.", array()),
                "csv" to prop("string", "CSV or TSV, columns named on its first line as in `columns`; not rows."),
                if (local) {
                    "file" to prop("string", "A CSV or TSV file here, columns named on its first line; not rows.")
                } else {
                    null
                },
                "tags" to array("For every note."),
                "on_duplicate" to buildJsonObject {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("skip")
                        add("add")
                    }
                },
                required = listOf("deck_id", "template_id"),
            ),
        ) { call ->
            val a = call.arguments
            val template = ws.templates().firstOrNull { it.id == a.str("template_id") }
                ?: return@addTool refused("No such template.")
            val decks = ws.decks().map { it.deck }
            val deck = decks.firstOrNull { it.id == a.str("deck_id") } ?: return@addTool refused("No such deck.")
            val file = a.str("file")?.takeIf { local }
            val csv =
                a.str("csv")
                    ?: file?.let {
                        File(it).takeIf(File::isFile)?.readText() ?: return@addTool refused("No such file.")
                    }
            val table = csv?.let {
                runCatching { Spreadsheet(it, header = true) }.getOrNull()
                    ?: return@addTool refused("That CSV has a quote left open.")
            }
            if (table != null && table.html && html == null) {
                return@addTool refused("Its cells are HTML. Send them as markup.")
            }
            val cell: (String) -> String = if (table?.html == true) html!! else { v -> v }
            val (header, rows) = when {
                table != null -> table.rows.first() to table.body

                else -> a.list("columns").ifEmpty { template.fields.map { it.name } } to
                    a?.get("rows")?.jsonArray.orEmpty().map { r -> r.jsonArray.map { it.jsonPrimitive.content } }
            }
            val tagsAt = header.indexOfFirst { it.equals("tags", ignoreCase = true) && template.field(it) == null }
            val fields = header.map { template.field(it)?.id?.toString() }
            val unknown = header.filterIndexed { i, _ -> fields[i] == null && i != tagsAt }
            if (unknown.isNotEmpty()) {
                val known = template.fields.joinToString(", ") { it.name }
                return@addTool refused(
                    "No field ${unknown.joinToString(", ") {
                        "“$it”"
                    }}. ${template.name} has $known.",
                )
            }
            val shared = a.list("tags")
            // Keys already there, then the batch's own as it goes; none when duplicates are wanted.
            val seen = if (a.str("on_duplicate") == "add") {
                null
            } else {
                ws.notes(template.repeats(Tree(decks), deck.id), 100_000)
                    .mapNotNull { template.key(it.fields) }.toHashSet()
            }
            val made = ArrayList<Pair<Int, Note>>()
            val skipped = ArrayList<String>()
            val wrong = ArrayList<String>()
            for ((i, values) in rows.withIndex()) {
                if (values.size > header.size) {
                    wrong += "row ${i + 1} (${values.size} values for ${header.size} columns)"
                    continue
                }
                val given = values.withIndex().mapNotNull { (c, v) -> fields[c]?.let { it to cell(v) } }.toMap()
                val note = Note(
                    Uuid.generateV7().toString(),
                    deck.id,
                    template.id,
                    given.filterValues { it.isNotBlank() },
                    ws.clock(),
                    deck.ownerId,
                    tags = Tags(shared + listOfNotNull(values.getOrNull(tagsAt))).text,
                )
                val key = template.key(note.fields)
                if (seen != null && key != null &&
                    !seen.add(key)
                ) {
                    skipped += "row ${i + 1} ($key)"
                } else {
                    made += i to note
                }
            }
            val rowOf = made.associate { (i, n) -> n.id to "row ${i + 1}" }
            val rejected = ws.save(notes = made.map { it.second })
            report(
                "Added",
                made.size - rejected.size,
                "Skipped, already there" to skipped,
                "Refused" to wrong + rejected.named { rowOf.getValue(it) },
            )
        }

        addTool(
            "update_notes",
            "Fields left out stay; tags, when given, replace the note's.",
            schema(
                "notes" to array(
                    null,
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            put("id", text)
                            put("fields", fieldValues)
                            put("tags", array())
                        }
                        putJsonArray("required") { add("id") }
                    },
                ),
                required = listOf("notes"),
            ),
        ) { call ->
            val changes = call.arguments?.get("notes")?.jsonArray.orEmpty().map { it.jsonObject }
            val stored = ws.notes(changes.mapNotNull { it.str("id") }).associateBy { it.id }
            val templates = ws.templates().associateBy { it.id }
            val wrong = ArrayList<String>()
            val notes = changes.mapNotNull { c ->
                val n = stored[c.str("id")] ?: return@mapNotNull null
                val template = templates.getValue(n.templateId)
                val given = c["fields"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content }
                val unknown = given.keys.filter { template.field(it) == null }
                if (unknown.isNotEmpty()) {
                    wrong += "${n.id} (no field ${unknown.joinToString(", ") { "“$it”" }})"
                    return@mapNotNull null
                }
                val fields = n.fields + given.mapKeys { template.field(it.key)!!.id.toString() }
                val tags = if ("tags" in c) Tags(c.list("tags")).text else n.tags
                n.copy(fields = fields.filterValues { it.isNotBlank() }, tags = tags, v = ws.clock())
            }
            val rejected = ws.save(notes = notes)
            report(
                "Updated",
                notes.size - rejected.size,
                "Not found" to changes.mapNotNull { it.str("id") }.filter { it !in stored },
                "Refused" to wrong + rejected.named(),
            )
        }

        addTool(
            "move_notes",
            "To another deck of the same owner.",
            schema("ids" to array(), "deck_id" to text, required = listOf("ids", "deck_id")),
        ) { call ->
            val target = call.arguments.str("deck_id") ?: return@addTool refused("No such deck.")
            writeNotes(call.arguments.list("ids"), "Moved") { it.copy(deckId = target) }
        }

        addTool("delete_notes", "Restorable for 30 days.", schema("ids" to array(), required = listOf("ids"))) { call ->
            writeNotes(call.arguments.list("ids"), "Deleted") { it.copy(deletedAt = now()) }
        }

        addTool(
            "export_csv",
            "A deck's notes, with its sub-decks', as CSV: one a note type, by its name. Columns are the fields, then " +
                "Tags; values are markup. create_notes takes it back.",
            schema("deck_id" to text, required = listOf("deck_id")),
        ) { call ->
            val tree = Tree(ws.decks().map { it.deck })
            val id = call.arguments.str("deck_id")
            if (id == null || tree[id] == null) return@addTool refused("No such deck.")
            val notes = ws.notes(NoteFilter(decks = tree.subtree(id).map { it.id }), 100_000)
            val templates = ws.templates().associateBy { it.id }
            ok(
                buildJsonObject {
                    for ((t, group) in notes.groupBy { it.templateId }) {
                        val template = templates[t] ?: continue
                        put(template.name, Spreadsheet(template, group).text.removePrefix("\uFEFF"))
                    }
                },
            )
        }

        addTool(
            "due_today",
            "New, learning and review cards for today.",
            schema("deck_id" to prop("string", "All decks when left out.")),
        ) { call ->
            ok(due(call.arguments.str("deck_id")))
        }
    }

    /** Changes the notes [ids] by [change], and says how it went. */
    private suspend fun writeNotes(ids: List<String>, verb: String, change: (Note) -> Note): CallToolResult {
        val notes = ws.notes(ids).map { change(it).copy(v = ws.clock()) }
        val rejected = ws.save(notes = notes)
        val found = notes.map { it.id }.toSet()
        return report(
            verb,
            notes.size - rejected.size,
            "Not found" to ids.filter { it !in found },
            "Refused" to rejected.named(),
        )
    }

    private fun describe(t: Template) = buildJsonObject {
        put("id", t.id)
        put("name", t.name)
        putJsonArray("fields") {
            for (f in t.fields) {
                addJsonObject {
                    put("name", f.name)
                    put("kind", f.kind.value)
                }
            }
        }
        val cards = when (t) {
            is RondoTemplate -> t.cards.map { it.name }
            is AnkiTemplate -> t.cards.map { it.name }
        }
        putJsonArray("cards") { cards.forEach { add(it) } }
    }

    /**
     * [notes] as a table per template: id, deck, tags, then the fields ([only] those, when named).
     * [next]: where the next page starts.
     */
    private suspend fun notes(notes: List<Note>, tree: Tree, only: List<String>, next: String? = null) =
        buildJsonObject {
            val templates = ws.templates().associateBy { it.id }
            putJsonArray("notes") {
                for ((id, group) in notes.groupBy { it.templateId }) {
                    val t = templates[id]
                    // Field id to name; a template no longer seen shows its notes' field ids.
                    val all = t?.fields?.map { it.id.toString() to it.name }
                        ?: group.flatMap { it.fields.keys }.distinct().map { it to it }
                    val shown = all.filter { (_, name) ->
                        only.isEmpty() || only.any { it.equals(name, ignoreCase = true) }
                    }
                    val rows = group.map { n ->
                        listOf(n.id, tree.path(n.deckId).joinToString(" / "), n.tags)
                            .plus(shown.map { (field, _) -> n.fields[field].orEmpty() })
                            .map(::JsonPrimitive)
                    }
                    add(
                        buildJsonObject {
                            put("template", t?.name ?: id)
                            table(
                                listOf("id", "deck", "tags") + shown.map {
                                    it.second
                                },
                                rows,
                            ).forEach { (k, v) -> put(k, v) }
                        },
                    )
                }
            }
            next?.let { put("next", it) }
        }

    private fun Boolean.toInt() = if (this) 1 else 0

    /** Today's counts, folded from events exactly as devices do. */
    private suspend fun due(deck: String?): JsonObject {
        val now = now()
        val days = Days(ws.settingsTimezone())
        val today = days.start(days.index(now))
        val tree = Tree(ws.decks().map { it.deck })
        val roots = deck?.let(::listOf) ?: tree.roots.map { it.id }
        val decks = roots.flatMap { tree.subtree(it) }
        val notes = ws.notes(NoteFilter(decks = decks.map { it.id }), 100_000)
        val templates = ws.templates().associateBy { it.id }
        val events = ws.events(notes.map { it.id } + decks.map { it.id })
        val history = History(events)
        val fsrs = Fsrs()
        val loads = HashMap<String, DeckLoad>()
        for (n in notes) {
            val t = templates[n.templateId] ?: continue
            var learned = false
            for (card in t.cards(n)) {
                val s = history.card(n.id, card).state(fsrs)
                learned = learned || s.learnedAt != null
                if (s.suspended) continue
                val m = s.memory
                val due = m.due ?: 0
                val l = loads[n.deckId] ?: DeckLoad()
                loads[n.deckId] = l.copy(
                    newAvailable = l.newAvailable + (m.state == Memory.NEW).toInt(),
                    learningDue =
                    l.learningDue + (m.state != Memory.NEW && m.state != Memory.REVIEW && due <= now).toInt(),
                    reviewDue = l.reviewDue + (m.state == Memory.REVIEW && due < today + Days.DAY).toInt(),
                    newToday = l.newToday + ((s.introducedAt ?: 0) >= today).toInt(),
                )
            }
            val l = loads[n.deckId] ?: DeckLoad()
            loads[n.deckId] = l.copy(notes = l.notes + 1, learnedNotes = l.learnedNotes + learned.toInt())
        }
        val unlocked = events.filterIsInstance<Event.Unlock>().map { it.subjectId }.toSet()
        val levels = tree.levels({ loads[it] ?: DeckLoad() }, unlocked)
        return buildJsonObject {
            for (root in roots) {
                putJsonObject(tree[root]?.name ?: root) {
                    val c = tree.counts(root, { loads[it] ?: DeckLoad() }, levels)
                    put("new", c.new)
                    put("learning", c.learning)
                    put("review", c.review)
                }
            }
        }
    }

    private fun now() = Clock.System.now().toEpochMilliseconds()

    private companion object {
        const val INSTRUCTIONS = """Decks nest; a note fills its template's fields and makes one or more cards.
Field markup: **bold** *italic* __underline__ ==highlight== `code` ~sub~ ^sup^ ${'$'}math${'$'},
${'$'}${'$'} display math ${'$'}${'$'} on its own lines, {{c1::answer::hint}}, {base|ruby}, lines starting "- " or
"1. " for lists, pipe tables; a backslash escapes. Image and audio fields hold a media hash. Tags have no spaces; "::"
nests them (Cardio::Valves), and a tag finds those under it. Lists come back as columns and rows."""

        /** The most notes [search_notes] returns at once. */
        const val PAGE = 500

        /** The most items a write's report names. */
        const val NAMED = 20
    }
}
