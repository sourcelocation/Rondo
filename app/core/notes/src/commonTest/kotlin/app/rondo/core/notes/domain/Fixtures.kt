package app.rondo.core.notes.domain

import app.rondo.core.decks.domain.Deck
import kotlin.uuid.Uuid

fun newId() = Uuid.generateV7().toString()

fun deck(owner: String?, parent: String? = null) =
    Deck(newId(), "Deck", "V", 0, 20, 200, 90, false, 100, 1, owner, parent)

fun note(
    deck: Deck,
    template: String = Template.BASIC.id,
    fields: Map<String, String> = mapOf(
        "1" to "front",
        "2" to "back",
    ),
) = Note(newId(), deck.id, template, fields, 1, deck.ownerId)
