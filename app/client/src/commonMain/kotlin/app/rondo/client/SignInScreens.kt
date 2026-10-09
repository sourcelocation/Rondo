package app.rondo.client

import app.rondo.core.model.ConsentDecision
import app.rondo.core.model.ResetConfirm
import app.rondo.core.model.ResetRequest
import app.rondo.core.nowMillis
import kotlin.js.JsExport
import kotlinx.coroutines.CancellationException

/**
 * [step]: email, code, second (the authenticator app or a recovery code), lost (asking to remove a
 * lost second step), lost_code, lost_sent, or done. [error]: why the last try didn't work.
 * [resendAt]: when a new code may be sent.
 */
@JsExport
class SignInState internal constructor(
    val step: String,
    val email: String,
    val busy: Boolean,
    val error: String?,
    val resendAt: Double,
)

/** Signing in or up: an email code, a passkey, or Apple's or Google's ID token. */
@JsExport
class SignInScreen internal constructor(override val app: App) : Screen<SignInState>() {
    override val live get() = false
    private val account get() = app.rondo.account
    private var flow: CodeFlow? = null
    private var busy = false
    private var done = false
    private var error: String? = null
    private var sentAt = 0L

    /** Signed in, but the second step is still due; or the person lost it ([lost] the step of that). */
    private var second: Boolean? = null
    private var lost: String? = null
    private var lostEmail = ""

    override suspend fun load(): SignInState {
        if (second == null) second = account.needsSecondFactor()
        val step = when {
            done -> "done"
            lost != null -> lost!!
            second == true -> "second"
            flow != null -> "code"
            else -> "email"
        }
        val email = if (lost != null) lostEmail else flow?.email ?: account.email.orEmpty()
        return SignInState(step, email, busy, error, (sentAt + RESEND_AFTER).toDouble())
    }

    private fun step(block: suspend () -> Unit) = act {
        busy = true
        error = null
        show(load())
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = app.problem(e)
        } finally {
            busy = false
            show(load())
        }
    }

    private suspend fun signedIn() {
        if (account.needsSecondFactor()) {
            second = true
            return
        }
        second = false
        done = true
        app.reload()
        app.rondo.syncNow()
    }

    /** The second step: a [code] from the authenticator app, or a [recovery] code. */
    fun secondStep(code: String, recovery: Boolean) = step {
        account.secondFactor(code, recovery)
        signedIn()
    }

    /** The second step is lost: asks to remove it (a week later, by email). */
    fun lostSecondStep() = act {
        lost = "lost"
        lostEmail = flow?.email ?: account.email.orEmpty()
        error = null
        show(load())
    }

    /** Sends a code to [email] to confirm removing its account's second step. */
    fun requestReset(email: String) = step {
        online { app.rondo.net.api.requestReset(ResetRequest(email.trim())).ok() }
        lostEmail = email.trim()
        lost = "lost_code"
    }

    fun confirmReset(code: String) = step {
        online { app.rondo.net.api.confirmReset(ResetConfirm(lostEmail, code.trim())).ok() }
        lost = "lost_sent"
    }

    fun sendCode(email: String) = step {
        flow = account.sendCode(email.trim())
        sentAt = nowMillis()
    }

    /** Sends another code to the same address, once [SignInState.resendAt] has passed. */
    fun resend() = step {
        val email = flow?.email ?: return@step
        if (nowMillis() < sentAt + RESEND_AFTER) return@step
        flow = account.sendCode(email)
        sentAt = nowMillis()
    }

    fun confirm(code: String) = step {
        account.confirm(flow ?: return@step, code)
        signedIn()
    }

    fun back() = act {
        flow = null
        lost = null
        error = null
        show(load())
    }

    private companion object {
        const val RESEND_AFTER = 30_000L
    }

    fun passkey() = step {
        val flow = account.passkey()
        account.finishPasskey(flow, app.rondo.platform.passkey(flow.options, register = false))
        signedIn()
    }

    /** An ID token from Apple's or Google's own sign-in ([provider] apple or google). */
    fun idToken(provider: String, token: String, nonce: String?) = step {
        account.idToken(provider, token, nonce)
        signedIn()
    }
}

@JsExport
class ConsentState internal constructor(
    val client: String?,
    val uri: String?,
    val scopes: Array<String>,
    val signedIn: Boolean,
)

/** An agent asking for access through OAuth: allow or deny, then back to it. */
@JsExport
class ConsentScreen internal constructor(override val app: App, private val challenge: String) :
    Screen<ConsentState>() {
    override val live get() = false

    override suspend fun load(): ConsentState {
        if (!app.rondo.account.signedIn) return ConsentState(null, null, emptyArray(), false)
        val c = online { app.rondo.net.api.getConsent(challenge) }.takeIf { it.status != 404 }?.ok()
        return ConsentState(c?.clientName, c?.clientUri, c?.scopes.orEmpty().toTypedArray(), true)
    }

    fun decide(allow: Boolean, open: (String) -> Unit) = act {
        open(online { app.rondo.net.api.decideConsent(challenge, ConsentDecision(allow)).ok() }.url)
    }
}
