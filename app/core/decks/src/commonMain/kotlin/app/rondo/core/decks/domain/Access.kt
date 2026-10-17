package app.rondo.core.decks.domain

import app.rondo.core.kernel.domain.ErrorCode

/**
 * Someone's part in a deck: its owner, an editor or a viewer it's lent to, a reader once its author
 * removed it, or none.
 */
enum class Role { NONE, READ_ONLY, VIEWER, EDITOR, OWNER }

/**
 * Who may do what, for [actor]: [lends] maps each deck lent to them to its role (1 viewer,
 * 2 editor); an editor writes only while the owner has Pro ([pro]).
 */
class Access(val actor: String, val tree: Tree, val lends: Map<String, Int>, val pro: (String) -> Boolean) {
    fun role(deckId: String?): Role {
        val deck = tree[deckId] ?: return Role.NONE
        val owner = deck.ownerId ?: return Role.READ_ONLY
        if (owner == actor) return Role.OWNER
        for (d in listOf(deck) + tree.ancestors(deck.id)) {
            val lent = lends[d.id] ?: continue
            return if (lent == 2 && pro(owner)) Role.EDITOR else Role.VIEWER
        }
        return Role.NONE
    }

    fun canWrite(deckId: String?): ErrorCode? = when (role(deckId)) {
        Role.OWNER, Role.EDITOR -> null
        Role.READ_ONLY -> ErrorCode.READ_ONLY
        Role.NONE -> if (tree[deckId] == null) ErrorCode.NOT_FOUND else ErrorCode.FORBIDDEN
        Role.VIEWER -> ErrorCode.FORBIDDEN
    }
}
