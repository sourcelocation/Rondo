package app.rondo.core.kernel.infrastructure

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Rondo's data as it's stored: one record per table row, with the table's columns. Postgres reads
 * and writes them as JSON, Rondo files hold them, and sync carries them, tagged by kind when a list
 * mixes kinds. Each slice keeps the records of its own tables, converting to and from its domain
 * objects; the domain never sees a column. The sync protocol knows every kind.
 */
@Serializable
abstract class Record {
    abstract val id: String

    /** Its clock, for last-writer-wins; null for what's only ever added (events). */
    abstract val v: Long?

    companion object {
        /** How records are written: every value but absent ones, and unknown ones (from newer apps) skipped. */
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
    }
}
