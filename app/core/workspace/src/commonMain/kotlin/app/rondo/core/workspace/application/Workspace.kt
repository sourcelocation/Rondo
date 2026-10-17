package app.rondo.core.workspace.application

import app.rondo.core.decks.domain.Deck
import app.rondo.core.decks.domain.Role
import app.rondo.core.kernel.domain.ErrorCode
import app.rondo.core.notes.domain.Note
import app.rondo.core.notes.domain.NoteFilter
import app.rondo.core.notes.domain.Template
import app.rondo.core.study.domain.Event

/** A deck as an agent or a list sees it. */
data class DeckView(val deck: Deck, val path: List<String>, val role: Role, val notes: Int)

/**
 * One account's data, wherever it lives: a device's SQLite (the command-line MCP client) or the
 * server's Postgres (hosted MCP). What it saves always passes each thing's own check first.
 */
interface Workspace {
    val actor: String

    suspend fun decks(): List<DeckView>

    suspend fun templates(): List<Template>

    /** Notes [filter] finds, in the order they were added. */
    suspend fun notes(filter: NoteFilter, limit: Int): List<Note>

    suspend fun notes(ids: List<String>): List<Note>

    /** The events of [noteIds]' cards. */
    suspend fun events(noteIds: List<String>): List<Event>

    suspend fun settingsTimezone(): String?

    /** Keeps [decks] and [notes], each if its check passes; what was refused, by id. */
    suspend fun save(decks: List<Deck> = emptyList(), notes: List<Note> = emptyList()): Map<String, ErrorCode>

    /** A clock value newer than anything this workspace has seen. */
    fun clock(): Long
}
