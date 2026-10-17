package app.rondo.core.kernel.domain

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
