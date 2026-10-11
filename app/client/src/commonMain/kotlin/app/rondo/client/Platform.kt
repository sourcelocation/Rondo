package app.rondo.client

import app.cash.sqldelight.db.SqlDriver

/** What each platform provides. */
interface Platform {
    /** The origin `/api`, `/sync` and `/media` are served from, unless Settings chose another. */
    val apiUrl: String
    val kratosUrl: String

    /** Whether people may choose a server of their own: not where that server serves the app (the web). */
    val serverChoice: Boolean get() = true

    suspend fun driver(): SqlDriver

    /** Small values kept outside the database: the session token. */
    fun read(key: String): String?

    fun write(key: String, value: String?)

    /** The files in a zip archive, by name: Anki packages and Rondo files. */
    suspend fun unzip(bytes: ByteArray): Map<String, ByteArray>

    /** [files] by name as a zip archive. */
    suspend fun zip(files: Map<String, ByteArray>): ByteArray

    fun unzstd(bytes: ByteArray): ByteArray

    /** A SQLite database held in [bytes], to read from: an Anki collection. An empty one to write without. */
    suspend fun openSqlite(bytes: ByteArray = ByteArray(0)): SqlDriver

    /** The database [driver] (from [openSqlite]) holds now, as a file; it closes it. */
    suspend fun sqliteBytes(driver: SqlDriver): ByteArray

    /** Runs a WebAuthn ceremony with Kratos's [options] JSON and returns the credential as JSON. */
    suspend fun passkey(options: String, register: Boolean): String

    /** Reminders the device schedules itself; null where it can't (browsers, the JVM). */
    val notifications: LocalNotifications? get() = null

    /** Pushes from the server; null where there are none. */
    val push: PushChannel? get() = null
}

/** A reminder the device shows itself at [at] (epoch milliseconds). */
class LocalReminder(val at: Long, val title: String, val body: String)

/** Reminders a device schedules itself, without the server: the phone apps. */
interface LocalNotifications {
    /** Whether reminders may show; [ask] asks the person when they haven't said. */
    suspend fun allowed(ask: Boolean): Boolean

    /** Replaces every scheduled reminder with [reminders]. */
    suspend fun schedule(reminders: List<LocalReminder>)
}

/** Where the server pushes to this device: [platform] is web, apns or fcm; browsers add their keys. */
class PushTarget(val platform: String, val endpoint: String, val p256dh: String? = null, val auth: String? = null)

/** Pushes from the server: Web Push in browsers, APNs and FCM in the apps. */
interface PushChannel {
    /**
     * This device's subscription, made with the server's [key] when there's none yet and [ask]
     * allows asking the person; null when they said no, or weren't asked.
     */
    suspend fun subscribe(key: String, ask: Boolean): PushTarget?

    suspend fun unsubscribe()

    /** Whether pushes can arrive here now (a browser needs its service worker). */
    suspend fun available(): Boolean
}
