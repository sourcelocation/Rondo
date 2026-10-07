package app.rondo.core

/** Unicode canonical decomposition (NFD). */
internal expect fun decompose(s: String): String

private val COMBINING = Regex("\\p{M}+")

/** Lower case without accents: what search and type-in answers compare. */
fun fold(s: String): String = decompose(s).replace(COMBINING, "").lowercase()

/** The text a note is found by: every text field, folded. */
fun searchText(fields: Map<String, String>, textFields: Set<String>): String =
    fields.filterKeys { it in textFields }.values.joinToString(" ") { fold(Markup.plain(it)).replace('\n', ' ') }
