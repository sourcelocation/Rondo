package app.rondo.client

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.rondo.client.db.Media
import app.rondo.core.model.UploadRequest
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import okio.ByteString.Companion.toByteString

/**
 * Media files by SHA-256: kept in the database, uploaded before the notes that use them, and
 * downloaded from `/media/{hash}` the first time a borrowed card shows one.
 */
class MediaFiles(private val store: Store, private val net: Net) {
    private val q = store.q

    suspend fun add(bytes: ByteArray, mime: String): String {
        val hash = bytes.toByteString().sha256().hex()
        if (q.media(hash).awaitAsOneOrNull() == null) q.putMedia(Media(hash, mime, bytes, false))
        return hash
    }

    suspend fun get(hash: String): Media? = q.media(hash).awaitAsOneOrNull() ?: runCatching {
        val response = net.http.get("${net.apiUrl}/media/$hash")
        if (!response.status.isSuccess()) return null
        val bytes = response.readRawBytes()
        if (bytes.toByteString().sha256().hex() != hash) return null
        Media(hash, response.headers["Content-Type"] ?: "application/octet-stream", bytes, true).also { q.putMedia(it) }
    }.getOrNull()

    suspend fun upload() {
        for (pending in q.unuploaded().awaitAsList()) {
            val bytes = q.media(pending.hash).awaitAsOneOrNull()?.bytes ?: continue
            val target = net.api.requestUpload(UploadRequest(pending.hash, bytes.size, pending.mime)).ok()
            if (!target.exists) {
                val sent = net.http.put(target.url ?: continue) {
                    headers { target.headers?.forEach { (k, v) -> append(k, v) } }
                    setBody(bytes)
                }
                if (!sent.status.isSuccess()) continue
                net.api.completeUpload(pending.hash).ok()
            }
            q.markUploaded(pending.hash)
        }
    }
}
