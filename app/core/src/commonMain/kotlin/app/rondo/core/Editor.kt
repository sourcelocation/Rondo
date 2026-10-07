package app.rondo.core

import kotlin.js.JsExport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Markup to and from ProseMirror documents (what TipTap edits), so editors only wire commands.
 * Node and mark names are TipTap's: paragraph, hardBreak, bulletList, orderedList, listItem, table,
 * tableRow, tableHeader, tableCell, inlineMath, blockMath; marks bold, italic, underline, highlight,
 * code, subscript, superscript, plus Rondo's cloze mark and ruby node.
 */
@JsExport
object Editor {
    private val MARKS = listOf(
        Run.BOLD to "bold",
        Run.ITALIC to "italic",
        Run.UNDERLINE to "underline",
        Run.HIGHLIGHT to "highlight",
        Run.CODE to "code",
        Run.SUB to "subscript",
        Run.SUP to "superscript",
    )

    fun toDocument(markup: String): String = buildJsonObject {
        put("type", "doc")
        put("content", JsonArray(Markup.parse(markup).map(::block).ifEmpty { listOf(paragraph(emptyArray())) }))
    }.toString()

    fun toMarkup(document: String): String {
        val doc = Json.parseToJsonElement(document).jsonObject
        return Markup.write(doc.children().mapNotNull(::fromBlock))
    }

    private fun block(b: TextBlock): JsonObject = when (b.kind) {
        TextBlock.MATH -> node("blockMath", attrs = buildJsonObject { put("latex", b.tex) })

        TextBlock.BULLETS, TextBlock.NUMBERS -> node(
            if (b.kind == TextBlock.BULLETS) "bulletList" else "orderedList",
            b.items.map { node("listItem", listOf(paragraph(it))) },
        )

        TextBlock.TABLE -> node(
            "table",
            b.rows.mapIndexed { r, row ->
                val cell = if (r == 0 && b.header) "tableHeader" else "tableCell"
                node("tableRow", row.map { node(cell, listOf(paragraph(it))) })
            },
        )

        else -> paragraph(b.runs)
    }

    private fun paragraph(runs: Array<Run>) = node("paragraph", runs.map(::inline))

    private fun inline(r: Run): JsonObject {
        val marks = buildJsonArray {
            for ((bit, name) in MARKS) if (r.marks and bit != 0) add(node(name))
            if (r.cloze > 0) {
                val attrs = buildJsonObject {
                    put("n", r.cloze)
                    r.hint?.let { put("hint", it) }
                }
                add(node("cloze", attrs = attrs))
            }
        }
        val base = when (r.kind) {
            Run.BREAK -> node("hardBreak")

            Run.MATH -> node("inlineMath", attrs = buildJsonObject { put("latex", r.text) })

            Run.RUBY -> node(
                "ruby",
                attrs = buildJsonObject {
                    put("base", r.text)
                    put("reading", r.reading.orEmpty())
                },
            )

            else -> buildJsonObject {
                put("type", "text")
                put("text", r.text)
            }
        }
        return if (marks.isEmpty()) base else JsonObject(base + ("marks" to marks))
    }

    private fun node(type: String, content: List<JsonObject>? = null, attrs: JsonObject? = null) = buildJsonObject {
        put("type", type)
        attrs?.let { put("attrs", it) }
        content?.let { put("content", JsonArray(it)) }
    }

    private fun JsonObject.children(): List<JsonObject> = this["content"]?.jsonArray?.map { it.jsonObject }.orEmpty()

    private fun JsonObject.type(): String = this["type"]?.jsonPrimitive?.content.orEmpty()

    private fun JsonObject.attr(name: String): String? = this["attrs"]?.jsonObject?.get(name)?.let {
        (it as? JsonPrimitive)?.takeUnless { p -> p.content == "null" }?.content
    }

    private fun fromBlock(n: JsonObject): TextBlock? = when (n.type()) {
        "paragraph" -> TextBlock(TextBlock.PARAGRAPH, runs(n))

        "blockMath" -> TextBlock(TextBlock.MATH, tex = n.attr("latex").orEmpty())

        "bulletList", "orderedList" -> TextBlock(
            if (n.type() == "bulletList") TextBlock.BULLETS else TextBlock.NUMBERS,
            items = n.children().map { item ->
                item.children().flatMap { runs(it).asList() }.toTypedArray()
            }.toTypedArray(),
        )

        "table" -> {
            val rows = n.children().map { row -> row.children() }
            TextBlock(
                TextBlock.TABLE,
                rows = rows.map { row ->
                    row.map { cell -> cell.children().flatMap { runs(it).asList() }.toTypedArray() }.toTypedArray()
                }.toTypedArray(),
                header = rows.firstOrNull()?.all { it.type() == "tableHeader" } == true,
            )
        }

        else -> null
    }

    private fun runs(paragraph: JsonObject): Array<Run> = paragraph.children().map { n ->
        val marks = n["marks"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        var bits = 0
        for (m in marks) MARKS.firstOrNull { it.second == m.type() }?.let { bits = bits or it.first }
        val cloze = marks.firstOrNull { it.type() == "cloze" }
        val number = cloze?.get("attrs")?.jsonObject?.get("n")?.jsonPrimitive?.int ?: 0
        val hint = cloze?.attr("hint")
        when (n.type()) {
            "hardBreak" -> Run("", kind = Run.BREAK)
            "inlineMath" -> Run(n.attr("latex").orEmpty(), bits, Run.MATH, cloze = number, hint = hint)
            "ruby" -> Run(n.attr("base").orEmpty(), bits, Run.RUBY, n.attr("reading"), number, hint)
            else -> Run(n["text"]?.jsonPrimitive?.content.orEmpty(), bits, cloze = number, hint = hint)
        }
    }.toTypedArray()
}
