package app.rondo.core.markup.domain

/**
 * [text] lower case and without accents: what search and type-in answers compare. With [accents]
 * they stay, in one spelling (canonically decomposed): what tells repeated notes apart.
 */
class Folded(text: String, accents: Boolean = false) {
    val text: String = decompose(text).let { if (accents) it else it.replace(COMBINING, "") }.lowercase()

    private companion object {
        val COMBINING = Regex("\\p{M}+")
    }
}

/** Unicode canonical decomposition (NFD), as each platform does it. */
internal expect fun decompose(s: String): String
