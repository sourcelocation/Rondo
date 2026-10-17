package app.rondo.core.files.application

import app.rondo.core.files.domain.Contents
import app.rondo.core.files.domain.MediaFile

/**
 * A deck written down as a file: read into [contents], or written from them into [files]. A file
 * only reads and writes; where its decks go, and what's already there, is the importer's.
 */
interface DeckFile {
    /** The decks it holds, each with everything under it. */
    suspend fun contents(): List<Contents>

    /** A media file it holds, by hash. */
    suspend fun media(hash: String): MediaFile?

    /** It, as files by name: zipping them is the caller's. */
    suspend fun files(): Map<String, ByteArray>
}
