package app.rondo.core.notes.domain

/**
 * One side of an Anki card template, read once: text, fields with their filters, and `{{#…}}` /
 * `{{^…}}` sections. Showing a note 1:1, deciding which cards it has, and converting it to Rondo's
 * blocks all read it, so they can't disagree.
 */
class AnkiSide(source: String) {
    val nodes: List<Node> = parse(source)

    sealed interface Node

    /** HTML between the template's tags. */
    class Text(val html: String) : Node

    /** `{{filters:Name}}`: a field, or one of Anki's own (`FrontSide`, `Tags`, `Deck`…). */
    class Field(val name: String, val filters: List<String>) : Node

    /** `{{#Name}}…{{/Name}}`, or with [inverted] `{{^Name}}…{{/Name}}`. */
    class Section(val field: String, val inverted: Boolean, val body: List<Node>) : Node

    /**
     * When a note has the card this side is the front of, as Anki decides: when every field of one
     * of these groups is filled. A field shown counts, with the `{{#…}}` sections around it; text,
     * Anki's own fields and `{{^…}}` conditions don't. Only names in [fields] are fields.
     */
    fun requirements(fields: Set<String>): List<Set<String>> {
        val groups = ArrayList<Set<String>>()
        fun walk(nodes: List<Node>, above: Set<String>) {
            for (n in nodes) {
                when (n) {
                    is Text -> Unit
                    is Field -> if (n.name in fields) groups += above + n.name
                    is Section -> walk(n.body, if (n.inverted || n.field !in fields) above else above + n.field)
                }
            }
        }
        walk(nodes, emptySet())
        // A group holding another is never the one that decides.
        return groups.distinct().filter { g -> groups.none { it != g && g.containsAll(it) } }
    }

    /** Whether a note with these [fields] (by name) has the card this side is the front of. */
    fun shows(fields: Map<String, String>): Boolean {
        val filled = fields.filterValues { !EMPTY.matches(it) }.keys
        return requirements(fields.keys).any { filled.containsAll(it) }
    }

    /** The fields shown with a filter of [filter] (`cloze`, `furigana`…), in order. */
    fun filtered(filter: String): List<String> = fields().filter { filter in it.filters }.map { it.name }

    /** Every field shown, sections opened, in order. */
    fun fields(): List<Field> {
        val out = ArrayList<Field>()
        fun walk(nodes: List<Node>) {
            for (n in nodes) {
                when (n) {
                    is Text -> Unit
                    is Field -> out += n
                    is Section -> walk(n.body)
                }
            }
        }
        walk(nodes)
        return out
    }

    /** The side as HTML for a note's [fields] (by name): card [card], with the front's HTML for `{{FrontSide}}`. */
    fun render(fields: Map<String, String>, card: Int, back: Boolean, frontSide: String): String {
        val out = StringBuilder()
        fun walk(nodes: List<Node>) {
            for (n in nodes) {
                when (n) {
                    is Text -> out.append(n.html)

                    is Field -> {
                        val value = if (n.name == FRONT_SIDE) frontSide else fields[n.name].orEmpty()
                        out.append(n.filters.foldRight(value) { f, v -> filter(f, v, card, back) })
                    }

                    is Section -> if (EMPTY.matches(fields[n.field].orEmpty()) == n.inverted) walk(n.body)
                }
            }
        }
        walk(nodes)
        return out.toString()
    }

    companion object {
        const val FRONT_SIDE = "FrontSide"
        private val TAG = Regex("<[^>]*>")
        private val CLOZE = Regex("\\{\\{c(\\d+)::([\\s\\S]*?)(?:::([\\s\\S]*?))?\\}\\}")
        private val FURIGANA = Regex(" ?([^ >\\[]+)\\[([^\\]]+)\\]")

        /** What Anki counts as an empty field: nothing but spaces, line breaks and empty `div`s. */
        private val EMPTY = Regex("^(?:\\s|</?(?:br|div) ?/?>)*$", RegexOption.IGNORE_CASE)

        /** A section being read: its field, and what's in it so far. */
        private class Open(val field: String, val inverted: Boolean) {
            val nodes = ArrayList<Node>()
        }

        /** Reads [source]; sections left open close at the end, and closing tags that open nothing are dropped. */
        private fun parse(source: String): List<Node> {
            val stack = arrayListOf(Open("", false))
            fun closeTo(depth: Int) {
                while (stack.size > depth) {
                    val done = stack.removeAt(stack.lastIndex)
                    stack.last().nodes += Section(done.field, done.inverted, done.nodes)
                }
            }
            var i = 0
            while (i < source.length) {
                val open = source.indexOf("{{", i)
                val close = if (open < 0) -1 else source.indexOf("}}", open + 2)
                if (open < 0 || close < 0) {
                    stack.last().nodes += Text(source.substring(i))
                    break
                }
                if (open > i) stack.last().nodes += Text(source.substring(i, open))
                val tag = source.substring(open + 2, close).trim()
                i = close + 2
                when {
                    tag.isEmpty() || tag.startsWith("!") || tag.startsWith("=") -> Unit

                    tag.startsWith("#") || tag.startsWith("^") -> stack += Open(tag.substring(1).trim(), tag[0] == '^')

                    tag.startsWith("/") -> {
                        val name = tag.substring(1).trim()
                        val at = stack.indexOfLast { it.field == name }
                        if (at > 0) closeTo(at)
                    }

                    else -> {
                        val parts = tag.split(':')
                        stack.last().nodes += Field(parts.last().trim(), parts.dropLast(1).map { it.trim() })
                    }
                }
            }
            closeTo(1)
            return stack.single().nodes
        }

        private fun filter(name: String, value: String, card: Int, back: Boolean): String = when (name) {
            "cloze" -> CLOZE.replace(value) { m ->
                val n = m.groupValues[1].toInt()
                when {
                    n != card -> m.groupValues[2]
                    back -> "<span class=\"cloze\">${m.groupValues[2]}</span>"
                    else -> "<span class=\"cloze\">[${m.groupValues[3].ifEmpty { "…" }}]</span>"
                }
            }

            "text" -> value.replace(TAG, "")

            "type" -> ""

            "furigana" -> FURIGANA.replace(value) { "<ruby>${it.groupValues[1]}<rt>${it.groupValues[2]}</rt></ruby>" }

            "kanji" -> FURIGANA.replace(value) { it.groupValues[1] }

            "kana" -> FURIGANA.replace(value) { it.groupValues[2] }

            else -> value
        }
    }
}
