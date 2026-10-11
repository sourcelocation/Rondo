package app.rondo.core

import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Why a change was refused, as the API names it ([value]). The spec's list and this one are kept equal by a test. */
enum class ErrorCode(val value: String) {
    UNAUTHORIZED("unauthorized"),
    FORBIDDEN("forbidden"),
    READ_ONLY("read_only"),
    NOT_FOUND("not_found"),
    OWNER_MISMATCH("owner_mismatch"),
    CYCLE("cycle"),
    INVALID("invalid"),
    TEMPLATE_IN_USE("template_in_use"),
    QUOTA_EXCEEDED("quota_exceeded"),
    TOO_LARGE("too_large"),
    CLOCK_AHEAD("clock_ahead"),
    CLIENT_OUTDATED("client_outdated"),
    PRO_REQUIRED("pro_required"),
    NAME_REQUIRED("name_required"),
    REAUTHENTICATE("reauthenticate"),
    CONFLICT("conflict"),
    UNAVAILABLE("unavailable"),
    INTERNAL("internal"),
    PASSKEY_REQUIRED("passkey_required"),
    FROZEN("frozen"),
    RATE_LIMITED("rate_limited"),
    SECOND_FACTOR_REQUIRED("second_factor_required"),
    USERNAME_TAKEN("username_taken"),
    USERNAME_INVALID("username_invalid"),
    TOO_SOON("too_soon"),
    PROFILE_REQUIRED("profile_required"),
    RESTRICTED("restricted"),
    NETWORK_RESTRICTED("network_restricted"),
    CODE_INVALID("code_invalid"),
    ALREADY_PRO("already_pro"),
}

/** A change the rules refused, before anything was written. */
class Refused(val code: ErrorCode) : Exception(code.value)

enum class Role { NONE, READ_ONLY, VIEWER, EDITOR, OWNER }

/**
 * Who may do what, for [actor]: [lends] maps each deck lent to them to its role (1 viewer,
 * 2 editor); an editor writes only while the owner has Pro ([pro]).
 */
class Access(val actor: String, val tree: Tree, val lends: Map<String, Int>, val pro: (String) -> Boolean) {
    fun role(deckId: String?): Role {
        val deck = tree[deckId] ?: return Role.NONE
        val owner = deck.ownerId ?: return Role.READ_ONLY
        if (owner == actor) return Role.OWNER
        for (d in listOf(deck) + tree.ancestors(deck.id)) {
            val lent = lends[d.id] ?: continue
            return if (lent == 2 && pro(owner)) Role.EDITOR else Role.VIEWER
        }
        return Role.NONE
    }

    fun canWrite(deckId: String?): ErrorCode? = when (role(deckId)) {
        Role.OWNER, Role.EDITOR -> null
        Role.READ_ONLY -> ErrorCode.READ_ONLY
        Role.NONE -> if (tree[deckId] == null) ErrorCode.NOT_FOUND else ErrorCode.FORBIDDEN
        Role.VIEWER -> ErrorCode.FORBIDDEN
    }
}

/**
 * A row's clock for last-writer-wins: milliseconds since 2025 (43 bits), a counter (6 bits) and the
 * device (4 bits) — 53 bits, exact in every runtime's numbers, JavaScript's included. Equal clocks
 * are harmless: the server keeps the first. [observe] keeps it ahead of every clock seen.
 */
class Hlc(private val device: Int, private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private var last = 0L

    init {
        require(device in 0..DEVICE_MASK)
    }

    fun next(): Long {
        val wall = (now() - EPOCH) shl 10 or device.toLong()
        last = if (wall > last) wall else ((last ushr 4) + 1 shl 4) or device.toLong()
        return last
    }

    fun observe(v: Long) {
        if (v > last) last = v
    }

    /** Whether [v] is from further ahead than any device's clock may be. */
    fun ahead(v: Long): Boolean = (v ushr 10) + EPOCH > now() + SKEW

    companion object {
        const val DEVICE_MASK = 15
        const val EPOCH = 1_735_689_600_000L

        /** How far a device's clock may run ahead of the server's. */
        const val SKEW = 10 * 60_000L
    }
}

/** UUIDv7s dated from [millis] on, one a millisecond: imports and copies keep their source's order. */
class DatedIds(private var millis: Long) {
    private var n = 0

    /** The next id; [at] dates it instead, with a counter keeping ids of one moment apart. */
    fun next(at: Long? = null): String {
        val time = at ?: millis++
        val random = Uuid.random().toByteArray()
        val bytes = ByteArray(16) { i -> if (i < 6) (time ushr (40 - 8 * i)).toByte() else random[i] }
        val count = if (at != null) n++ and 0xfff else 0
        bytes[6] = (0x70 or ((count ushr 8) and 0x0f)).toByte()
        bytes[7] = count.toByte()
        bytes[8] = (bytes[8].toInt() and 0x3f or 0x80).toByte()
        return Uuid.fromByteArray(bytes).toString()
    }
}
