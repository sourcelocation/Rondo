package app.rondo.client.storage

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.rondo.client.db.Media
import app.rondo.core.files.domain.MediaFile

/** Pictures and sounds on this device, by hash, and which of them the server has. */
class MediaShelf internal constructor(private val store: Store) {
    private val q get() = store.q

    suspend fun get(hash: String): MediaFile? = q.media(hash).awaitAsOneOrNull()?.let {
        MediaFile(it.hash, it.mime, it.bytes)
    }

    /** Keeps [file]; [uploaded] when it came from the server. Whether it wasn't kept already. */
    suspend fun put(file: MediaFile, uploaded: Boolean = false): Boolean {
        if (q.media(file.hash).awaitAsOneOrNull() != null) return false
        q.putMedia(Media(file.hash, file.mime, file.bytes, uploaded))
        return true
    }

    /** The files the server doesn't have yet: hash and type. */
    suspend fun unuploaded(): List<Pair<String, String>> = q.unuploaded().awaitAsList().map { it.hash to it.mime }

    suspend fun uploaded(hash: String) = q.markUploaded(hash)
}
