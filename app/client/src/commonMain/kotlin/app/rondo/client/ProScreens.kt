package app.rondo.client

import kotlin.js.JsExport

// Paying for Pro, the same wherever it's offered: Settings' plan, what needs Pro, a promo link.

/**
 * Pro, offered where something needs it: [reason] says what. [busy] while a checkout starts. Whether
 * you're signed in or have Pro already is in [AppState].
 */
@JsExport
class ProState internal constructor(val reason: String, val busy: Boolean)

/**
 * Pro, offered by what needs it: a [feature] shown locked (editors), and chosen anyway. What Pro adds
 * is [Strings.proFeatures].
 */
@JsExport
class ProScreen internal constructor(override val app: App, val feature: String) : Screen<ProState>() {
    override val live get() = false
    private var busy = false

    override suspend fun load(): ProState {
        val reason = when (feature) {
            "editors" -> strings.editorsNeedPro
            else -> strings.proPitch
        }
        return ProState(reason, busy)
    }

    /** Starts a Stripe checkout for [plan] (monthly or yearly); [open] goes to its page. */
    fun checkout(plan: String, open: (String) -> Unit) = act {
        if (busy) return@act
        busy = true
        try {
            show(load())
            open(app.rondo.billing.checkout(plan))
        } finally {
            busy = false
            show(load())
        }
    }
}

/** [until] (ISO): when the Pro the code gave ends, once redeemed. */
@JsExport
class RedeemState internal constructor(val code: String, val busy: Boolean, val until: String?, val signedIn: Boolean)

/** Redeeming a promo code for Pro: on the web only (the stores don't allow it in apps). */
@JsExport
class RedeemScreen internal constructor(override val app: App, private var code: String) : Screen<RedeemState>() {
    override val live get() = false
    private var busy = false
    private var until: String? = null

    override suspend fun load() = RedeemState(code, busy, until, app.rondo.account.signedIn)

    fun redeem(code: String) = act {
        this.code = code
        busy = true
        show(load())
        try {
            until = app.redeem(code).proUntil
        } finally {
            busy = false
            show(load())
        }
    }
}
