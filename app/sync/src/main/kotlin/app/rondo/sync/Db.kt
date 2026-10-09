package app.rondo.sync

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.ResultSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Postgres through JDBC. Rows travel as JSON: `to_jsonb` out, `jsonb_populate_record` in. */
class Db(url: String, poolSize: Int = 10) {
    private val pool = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = url
            maximumPoolSize = poolSize
            isAutoCommit = false
        },
    )

    /** The columns of each synced table, read once: upserts write every column. */
    private val upserts: Map<String, String> by lazy {
        transaction { c ->
            SYNCED.associateWith { table ->
                val sql = "SELECT column_name FROM information_schema.columns WHERE table_name = ? " +
                    "ORDER BY ordinal_position"
                val columns = c.query(sql, table) { it.getString(1) }
                val key = if (table == "settings") "user_id" else "id"
                val set = columns.filter { it != key }
                // Events are never changed once written; every other row is replaced by its newer version.
                val update = "UPDATE SET (${set.joinToString()}) = (${set.joinToString { "EXCLUDED.$it" }})"
                val conflict = if (table == "events") "NOTHING" else update
                "INSERT INTO $table SELECT * FROM jsonb_populate_record(NULL::$table, ?::jsonb) " +
                    "ON CONFLICT ($key) DO $conflict"
            }
        }
    }

    fun <T> transaction(repeatable: Boolean = false, block: (Connection) -> T): T = pool.connection.use { c ->
        if (repeatable) c.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
        try {
            block(c).also { c.commit() }
        } catch (e: Throwable) {
            c.rollback()
            throw e
        }
    }

    fun upsert(c: Connection, table: String, row: JsonObject) {
        c.exec(upserts.getValue(table), row.toString())
    }

    fun close() = pool.close()

    companion object {
        val SYNCED = listOf("decks", "templates", "notes", "events", "settings", "smart_decks")
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}

private fun Connection.bind(sql: String, args: Array<out Any?>) = prepareStatement(sql).apply {
    args.forEachIndexed { i, a ->
        when (a) {
            is Collection<*> -> setArray(i + 1, connection.createArrayOf("text", a.map(Any?::toString).toTypedArray()))
            else -> setObject(i + 1, a)
        }
    }
}

fun <T> Connection.query(sql: String, vararg args: Any?, row: (ResultSet) -> T): List<T> = bind(sql, args).use { s ->
    s.executeQuery().use { rs -> buildList { while (rs.next()) add(row(rs)) } }
}

fun Connection.exec(sql: String, vararg args: Any?): Int = bind(sql, args).use { it.executeUpdate() }

inline fun <reified T> Connection.rows(sql: String, vararg args: Any?): List<T> =
    query(sql, *args) { Db.json.decodeFromString<T>(it.getString(1)) }
