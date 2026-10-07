package app.rondo.client

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.squareup.zstd.okio.zstdDecompress
import java.io.File
import java.util.Properties
import java.util.zip.ZipInputStream
import okio.Buffer
import okio.buffer

/** Rondo on the JVM (the command-line MCP client, tests): a SQLite file and a properties file. */
class JvmPlatform(
    override val apiUrl: String,
    override val kratosUrl: String,
    private val database: String,
    private val settings: File?,
) : Platform {
    private val values = Properties().apply { settings?.takeIf { it.exists() }?.inputStream()?.use(::load) }

    override suspend fun driver(): SqlDriver =
        JdbcSqliteDriver(if (database == ":memory:") JdbcSqliteDriver.IN_MEMORY else "jdbc:sqlite:$database")

    override fun read(key: String): String? = values.getProperty(key)

    override fun write(key: String, value: String?) {
        if (value == null) values.remove(key) else values.setProperty(key, value)
        settings?.outputStream()?.use { values.store(it, "Rondo") }
    }

    override suspend fun unzip(bytes: ByteArray): Map<String, ByteArray> =
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.associate { it.name to zip.readBytes() }
        }

    override fun unzstd(bytes: ByteArray): ByteArray = Buffer().write(bytes).zstdDecompress().buffer().use {
        it.readByteArray()
    }

    override suspend fun openSqlite(bytes: ByteArray): SqlDriver {
        val file = File.createTempFile("rondo-import", ".db").apply { deleteOnExit() }
        file.writeBytes(bytes)
        return JdbcSqliteDriver("jdbc:sqlite:${file.path}")
    }

    override suspend fun passkey(options: String, register: Boolean): String =
        throw SignInError("failed", "Passkeys need a browser or a phone.")
}
