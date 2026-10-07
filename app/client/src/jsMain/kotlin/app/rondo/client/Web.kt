package app.rondo.client

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.worker.WebWorkerDriver
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise
import kotlinx.browser.localStorage
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.w3c.dom.Worker
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag

@JsModule("@js-joda/timezone")
@JsNonModule
private external object Zones

private fun ByteArray.u8(): Uint8Array = unsafeCast<Int8Array>().let { Uint8Array(it.buffer, it.byteOffset, it.length) }

private fun Uint8Array.bytes(): ByteArray = Int8Array(buffer, byteOffset, length).unsafeCast<ByteArray>()

/** SQLite has no booleans: the worker answers 0 and 1, which Kotlin/JS would compare and serialize as numbers. */
private class Booleans(private val driver: SqlDriver) : SqlDriver by driver {
    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ) = driver.executeQuery(identifier, sql, { c ->
        mapper(object : SqlCursor by c {
            override fun getBoolean(index: Int) = c.getLong(index)?.let { it != 0L }
        })
    }, parameters, binders)
}

/** Web Push through the service worker (src/sw.ts shows what arrives). */
private object WebPush : PushChannel {
    val supported: Boolean = js("'serviceWorker' in navigator && 'PushManager' in window").unsafeCast<Boolean>()

    override suspend fun available(): Boolean {
        val registered: Promise<Any?> = js("navigator.serviceWorker.getRegistration()")
        val registration: Any? = registered.await()
        return registration != null
    }

    // Awaited values go into locals before use: Kotlin/JS doesn't split a suspend call that sits
    // inside an expression on `dynamic`, and would use the suspension marker as the value.

    /** The service worker's push manager; `ready` waits for ever without one, so it's asked first. */
    private suspend fun manager(): dynamic {
        if (!available()) return null
        val ready: Promise<dynamic> = js("navigator.serviceWorker.ready")
        val registration: dynamic = ready.await()
        return registration.pushManager
    }

    private suspend fun existing(manager: dynamic): dynamic {
        if (manager == null) return null
        val subscription: dynamic = manager.getSubscription().unsafeCast<Promise<dynamic>>().await()
        return subscription
    }

    override suspend fun subscribe(key: String, ask: Boolean): PushTarget? {
        val manager: dynamic = manager()
        if (manager == null) return null
        var subscription: dynamic = existing(manager)
        if (subscription == null) {
            if (!ask) return null
            val options: dynamic = js("({ userVisibleOnly: true })")
            options.applicationServerKey = key
            val made = runCatching { manager.subscribe(options).unsafeCast<Promise<dynamic>>().await() }
            // Refused, or notifications are blocked here.
            subscription = made.getOrNull() ?: return null
        }
        val json: dynamic = subscription.toJSON()
        return PushTarget("web", json.endpoint.unsafeCast<String>(), json.keys.p256dh, json.keys.auth)
    }

    override suspend fun unsubscribe() {
        val manager: dynamic = manager()
        val subscription: dynamic = existing(manager)
        if (subscription != null) subscription.unsubscribe().unsafeCast<Promise<dynamic>>().await()
    }
}

/** Rondo in a browser: SQLite in a worker over OPFS, the session in localStorage. */
private class WebPlatform(
    override val apiUrl: String,
    override val kratosUrl: String,
    private val worker: () -> Worker,
) : Platform {
    override val push: PushChannel? = WebPush.takeIf { it.supported }

    override suspend fun driver(): SqlDriver = Booleans(WebWorkerDriver(worker()))

    override fun read(key: String): String? = localStorage.getItem("rondo.$key")

    override fun write(key: String, value: String?) {
        if (value == null) localStorage.removeItem("rondo.$key") else localStorage.setItem("rondo.$key", value)
    }

    override suspend fun unzip(bytes: ByteArray): Map<String, ByteArray> = suspendCoroutine { c ->
        unzip(bytes.u8()) { error, files ->
            if (error != null) {
                c.resumeWithException(Refused(app.rondo.core.model.ErrorCode.INVALID))
            } else {
                val names: Array<String> = js("Object.keys(files)")
                c.resume(names.associateWith { files[it].unsafeCast<Uint8Array>().bytes() })
            }
        }
    }

    override fun unzstd(bytes: ByteArray): ByteArray = decompress(bytes.u8()).bytes()

    /** A worker of its own holding the database in memory. */
    override suspend fun openSqlite(bytes: ByteArray): SqlDriver {
        val w = worker()
        val load: dynamic = js("({ action: 'load' })")
        load.bytes = bytes.u8()
        w.postMessage(load)
        return Booleans(WebWorkerDriver(w))
    }

    override suspend fun passkey(options: String, register: Boolean): String {
        val parsed: dynamic = JSON.parse(options)
        val key: dynamic = parsed.publicKey ?: parsed.credentialOptions.publicKey
        val credentials: dynamic = js("navigator.credentials")
        val type: dynamic = js("PublicKeyCredential")
        val request: dynamic = js("({})")
        request.publicKey =
            if (register) type.parseCreationOptionsFromJSON(key) else type.parseRequestOptionsFromJSON(key)
        val credential: dynamic = try {
            val ceremony: dynamic = if (register) credentials.create(request) else credentials.get(request)
            ceremony.unsafeCast<Promise<dynamic>>().await()
        } catch (e: Throwable) {
            throw SignInError("cancelled", e.message ?: "cancelled")
        }
        return JSON.stringify(credential.toJSON())
    }
}

/** Opens Rondo: [worker] makes a SQLite worker (the database file, or an import's copy). */
@OptIn(DelicateCoroutinesApi::class)
@JsExport
fun start(apiUrl: String, kratosUrl: String, worker: () -> Worker): Promise<App> = GlobalScope.promise {
    check(Zones != undefined)
    App(Rondo.open(WebPlatform(apiUrl, kratosUrl, worker)))
}

/** A media file as an object URL, from this device or downloaded once; null when it can't be had. */
@OptIn(DelicateCoroutinesApi::class)
@JsExport
fun media(app: App, hash: String): Promise<String?> = GlobalScope.promise {
    val m = app.rondo.media.get(hash) ?: return@promise null
    URL.createObjectURL(Blob(arrayOf(m.bytes.u8()), BlobPropertyBag(m.mime)))
}
