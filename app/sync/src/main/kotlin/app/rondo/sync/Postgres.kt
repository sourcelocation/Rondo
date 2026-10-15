package app.rondo.sync

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.net.URI
import java.sql.Connection
import java.sql.ResultSet
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject

/** Postgres through JDBC: a pool, and transactions. Rows travel as JSON: `to_jsonb` out, `jsonb_populate_record` in. */
class Postgres(url: String, poolSize: Int = 10) {
    private val pool = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = jdbc(url)
            maximumPoolSize = poolSize
            isAutoCommit = false
        },
    )

    /** Each synced table's upsert, made once from its columns: an upsert writes every column. */
    private val upserts = ConcurrentHashMap<String, String>()

    fun <T> transaction(repeatable: Boolean = false, block: (Sql) -> T): T = pool.connection.use { c ->
        if (repeatable) c.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
        try {
            block(Sql(c, this)).also { c.commit() }
        } catch (e: Throwable) {
            c.rollback()
            throw e
        }
    }

    /** Writes [row] into [table]: its newer version replaces the stored one, unless [table] keeps the first. */
    internal fun upsert(sql: Sql, table: Table, row: JsonObject) {
        val statement = upserts.getOrPut(table.name) {
            val columns = sql.query(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ? ORDER BY ordinal_position",
                table.name,
            ) { it.getString(1) }
            val set = columns.filter { it != table.key }
            val update = "UPDATE SET (${set.joinToString()}) = (${set.joinToString { "EXCLUDED.$it" }})"
            "INSERT INTO ${table.name} SELECT * FROM jsonb_populate_record(NULL::${table.name}, ?::jsonb) " +
                "ON CONFLICT (${table.key}) DO ${if (table.replaces) update else "NOTHING"}"
        }
        sql.exec(statement, row.toString())
    }

    fun close() = pool.close()

    private companion object {
        /** Go's `postgres://user:pass@host/db` URLs as JDBC's; JDBC ones as they are. */
        fun jdbc(url: String): String {
            if (url.startsWith("jdbc:")) return url
            val u = URI(url)
            val (user, password) = (u.userInfo ?: ":").split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val query = listOfNotNull("user=$user", "password=$password".takeIf { password.isNotEmpty() }, u.query)
            return "jdbc:postgresql://${u.host}:${if (u.port > 0) u.port else 5432}${u.path}?${query.joinToString("&")}"
        }
    }
}

/** A transaction's connection: statements with `?` arguments, collections bound as arrays. */
class Sql internal constructor(private val c: Connection, internal val postgres: Postgres) {
    private fun bind(sql: String, args: Array<out Any?>) = c.prepareStatement(sql).apply {
        args.forEachIndexed { i, a ->
            when (a) {
                is Collection<*> -> setArray(i + 1, c.createArrayOf("text", a.map(Any?::toString).toTypedArray()))
                else -> setObject(i + 1, a)
            }
        }
    }

    fun <T> query(sql: String, vararg args: Any?, row: (ResultSet) -> T): List<T> = bind(sql, args).use { s ->
        s.executeQuery().use { rs -> buildList { while (rs.next()) add(row(rs)) } }
    }

    fun exec(sql: String, vararg args: Any?): Int = bind(sql, args).use { it.executeUpdate() }
}
