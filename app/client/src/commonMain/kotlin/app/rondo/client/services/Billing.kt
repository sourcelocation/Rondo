package app.rondo.client.services

import app.rondo.client.net.Net
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Pro: what you have, paying for it with Stripe, and promo codes. */
class Billing(private val net: Net) {
    suspend fun get(): Subscription = net.get("/api/billing").read()

    /** A Stripe checkout's page, for [plan] (monthly or yearly). */
    suspend fun checkout(plan: String): String =
        net.post("/api/billing/stripe/checkout", Checkout(plan)).read<Redirect>().url

    /** Stripe's page for changing or cancelling the subscription. */
    suspend fun portal(): String = net.post("/api/billing/stripe/portal").read<Redirect>().url

    /** Redeems a promo code: the Pro it gives starts when the Pro there is ends. */
    suspend fun redeem(code: String): Subscription = net.post("/api/redeem", Redeem(code.trim())).read()

    @Serializable
    private class Checkout(val plan: String)

    @Serializable
    private class Redeem(val code: String)
}

/**
 * Whether you have Pro, until when (ISO), through what ([provider]: stripe, app-store,
 * google-play, promo or grant), and whether it [renews].
 */
@Serializable
class Subscription(
    val pro: Boolean,
    @SerialName("pro_until") val proUntil: String? = null,
    val provider: String? = null,
    val renews: Boolean? = null,
)
