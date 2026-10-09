package app.rondo.sync

import kotlin.concurrent.thread
import org.slf4j.LoggerFactory

/**
 * Hourly, on one replica at a time (an advisory lock): purge deletions older than 30 days, and
 * delete rows without an author that nobody borrows any more.
 */
class Maintenance(private val db: Db) {
    private val log = LoggerFactory.getLogger("rondo.maintenance")

    fun start() = thread(isDaemon = true, name = "maintenance") {
        while (true) {
            runCatching {
                run(System.currentTimeMillis() - 30L * 86_400_000)
            }.onFailure { log.error("maintenance failed", it) }
            Thread.sleep(3_600_000)
        }
    }

    fun run(cutoff: Long) = db.transaction { c ->
        if (!c.query("SELECT pg_try_advisory_xact_lock(4242)") { it.getBoolean(1) }.single()) return@transaction
        val purged = c.query(
            "WITH RECURSIVE gone AS (SELECT id FROM decks WHERE deleted_at < ? AND owner_id IS NOT NULL " +
                "UNION SELECT d.id FROM decks d JOIN gone ON d.parent_id = gone.id WHERE d.owner_id IS NOT NULL) " +
                "SELECT id FROM gone",
            cutoff,
        ) { it.getString(1) }
        c.exec(
            "UPDATE counters SET purged_seq = seq, seq = seq + 1 WHERE owner_id IN (" +
                "SELECT owner_id FROM decks WHERE id = ANY(?::uuid[]) " +
                "UNION SELECT owner_id FROM notes WHERE deleted_at < ? " +
                "UNION SELECT owner_id FROM templates WHERE deleted_at < ? " +
                "UNION SELECT owner_id FROM smart_decks WHERE deleted_at < ?)",
            purged,
            cutoff,
            cutoff,
            cutoff,
        )
        c.exec(
            "DELETE FROM notes WHERE deleted_at < ? OR (deck_id = ANY(?::uuid[]) AND owner_id IS NOT NULL)",
            cutoff,
            purged,
        )
        c.exec("DELETE FROM decks WHERE id = ANY(?::uuid[])", purged)
        c.exec("DELETE FROM templates WHERE deleted_at < ?", cutoff)
        c.exec("DELETE FROM smart_decks WHERE deleted_at < ?", cutoff)
        c.exec(
            "WITH RECURSIVE kept AS (SELECT deck_id AS id FROM shares " +
                "UNION SELECT d.id FROM decks d JOIN kept ON d.parent_id = kept.id) " +
                "DELETE FROM decks WHERE owner_id IS NULL AND id NOT IN (SELECT id FROM kept)",
        )
        c.exec(
            "DELETE FROM notes n WHERE owner_id IS NULL AND NOT EXISTS (SELECT 1 FROM decks d WHERE d.id = n.deck_id)",
        )
        c.exec(
            "DELETE FROM templates t WHERE owner_id IS NULL " +
                "AND NOT EXISTS (SELECT 1 FROM notes n WHERE n.template_id = t.id)",
        )
        c.exec("DELETE FROM note_media m WHERE NOT EXISTS (SELECT 1 FROM notes n WHERE n.id = m.note_id)")
        if (purged.isNotEmpty()) log.info("purged {} decks", purged.size)
    }
}
