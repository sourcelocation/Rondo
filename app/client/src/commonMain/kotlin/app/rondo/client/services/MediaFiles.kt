package app.rondo.client.services

import app.rondo.client.net.Net
import app.rondo.client.storage.Store
import app.rondo.core.files.domain.MediaFile
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import okio.ByteString.Companion.toByteString

/**
 * Pictures and sounds by SHA-256: kept on the device, uploaded before the notes that use them, and
 * downloaded from `/media/{hash}` the first time a borrowed card shows one.
 */
class MediaFiles(private val store: Store, private val net: Net) {
    /** Files shown before they're kept (an import's preview), by hash: never stored or uploaded. */
    private val shown = HashMap<String, MediaFile>()

    /** Keeps [bytes] of type [mime]; its hash. */
    suspend fun add(bytes: ByteArray, mime: String): String =
        hash(bytes).also { store.media.put(MediaFile(it, mime, bytes)) }

    /** Keeps [file]; whether it wasn't kept already. */
    suspend fun add(file: MediaFile): Boolean = store.media.put(file)

    /** Shows [file] until [unshow], without keeping it. */
    fun show(file: MediaFile) {
        shown[file.hash] = file
    }

    /** Forgets the files shown and not kept. */
    fun unshow() = shown.clear()

    suspend fun get(hash: String): MediaFile? = shown[hash] ?: store.media.get(hash) ?: runCatching {
        val response = net.http.get("${net.apiUrl}/media/$hash")
        if (!response.status.isSuccess()) return null
        val bytes = response.readRawBytes()
        if (hash(bytes) != hash) return null
        MediaFile(hash, response.headers["Content-Type"] ?: "application/octet-stream", bytes)
            .also { store.media.put(it, uploaded = true) }
    }.getOrNull()

    /** Uploads the files the server doesn't have, straight to storage, and says each arrived. */
    suspend fun upload() {
        for ((hash, mime) in store.media.unuploaded()) {
            val bytes = store.media.get(hash)?.bytes ?: continue
            val target = net.post("/api/media", UploadRequest(hash, bytes.size, mime)).read<UploadTarget>()
            if (!target.exists) {
                val sent = net.online {
                    net.http.put(target.url ?: return@online null) {
                        headers { target.headers?.forEach { (k, v) -> append(k, v) } }
                        setBody(bytes)
                    }
                }
                if (sent?.status?.isSuccess() != true) continue
                net.post("/api/media/$hash/complete")
            }
            store.media.uploaded(hash)
        }
    }

    /** A file's name: its SHA-256, in hex. */
    fun hash(bytes: ByteArray): String = bytes.toByteString().sha256().hex()

    @Serializable
    private class UploadRequest(val hash: String, val size: Int, val mime: String)

    /** Where to put a file's bytes, unless the server [exists] has them. */
    @Serializable
    private class UploadTarget(val exists: Boolean, val url: String? = null, val headers: Map<String, String>? = null)
}
