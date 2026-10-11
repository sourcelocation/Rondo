package app.rondo.client

import app.rondo.client.net.ApiError
import app.rondo.client.net.SignInError
import app.rondo.client.services.CodeFlow
import app.rondo.client.services.RecoveryCodes
import app.rondo.client.services.TotpSetup
import app.rondo.core.ErrorCode
import kotlin.js.JsExport
import kotlinx.coroutines.CancellationException

/** A device the account is signed in on: [since] ISO; [current] is this one. */
@JsExport
class DeviceItem internal constructor(
    val id: String,
    val device: String,
    val place: String?,
    val since: String?,
    val current: Boolean,
)

/**
 * The account's security. [setupQr] and [setupKey]: turning on the authenticator app, to scan or
 * type. [recoveryCodes]: just made, shown once. [emailSent]: a code went to the new address.
 * [confirming]: a change needs a recent sign-in, and a code went to the account's email.
 * [resetAt] (ISO): when the second step is removed, as someone asked. [links]: google, apple.
 */
@JsExport
class SecurityState internal constructor(
    val email: String?,
    val twoFactor: Boolean,
    val setupQr: String?,
    val setupKey: String?,
    val recoveryCodes: Array<String>?,
    val devices: Array<DeviceItem>,
    val links: Array<String>,
    val emailSent: String?,
    val confirming: Boolean,
    val resetAt: String?,
    val busy: Boolean,
)

/** The email, passkeys, linked accounts, the authenticator app and recovery codes, and signed-in devices. */
@JsExport
class SecurityScreen internal constructor(override val app: App) : Screen<SecurityState>() {
    override val live get() = false
    private val account get() = app.rondo.account
    private var setup: TotpSetup? = null
    private var codes: RecoveryCodes? = null
    private var emailSent: String? = null
    private var busy = false

    /** A change waiting for a recent sign-in, and the code flow that confirms it. */
    private var waiting: (suspend () -> Unit)? = null
    private var reauth: CodeFlow? = null

    override suspend fun load(): SecurityState {
        val twoFactor = runCatching { account.twoFactor() }.getOrDefault(false)
        val devices = runCatching { account.devices() }.getOrDefault(emptyList())
            .map { DeviceItem(it.id, it.name, it.place, it.since, it.current) }
        val links = runCatching { account.links() }.getOrDefault(emptyList())
        return SecurityState(
            account.email, twoFactor, setup?.qr, setup?.key, codes?.codes?.toTypedArray(), devices.toTypedArray(),
            links.toTypedArray(), emailSent, reauth != null, account.me?.secondFactorResetAt, busy,
        )
    }

    /**
     * Runs a change; when it needs a recent sign-in, sends a code to the account's email first and
     * runs it once [confirm] gets that code.
     */
    private fun step(block: suspend () -> Unit) = act {
        busy = true
        show(load())
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SignInError) {
            if (e.code != "reauthenticate") throw e
            waiting = block
            reauth = account.reauthenticate(account.email ?: throw e)
        } catch (e: ApiError) {
            if (e.code != ErrorCode.REAUTHENTICATE) throw e
            waiting = block
            reauth = account.reauthenticate(account.email ?: throw e)
        } finally {
            busy = false
            show(load())
        }
    }

    /** Confirms it's you with the code sent for a waiting change, then makes it. */
    fun confirm(code: String) = step {
        val flow = reauth ?: return@step
        account.confirm(flow, code)
        reauth = null
        waiting?.invoke()
        waiting = null
    }

    /**
     * Adds a passkey, made by the browser or the system. When the sign-in is too old to add it, the
     * one just made waits for the code, then is added as it is: the person never makes two.
     */
    fun addPasskey() = step {
        val flow = account.addPasskey()
        val credential = app.rondo.platform.passkey(flow.options, register = true)
        val add: suspend () -> Unit = {
            account.finishPasskey(flow, credential)
            app.say(strings.passkeyAdded)
        }
        try {
            add()
        } catch (e: SignInError) {
            if (e.code != "reauthenticate") throw e
            waiting = add
            reauth = account.reauthenticate(account.email ?: throw e)
        }
    }

    /** Starts turning on the authenticator app. */
    fun startTotp() = step { setup = account.startTotp() }

    /** Turns it on with a [code] it shows, then makes recovery codes to keep. */
    fun confirmTotp(code: String) = step {
        account.confirmTotp(setup ?: return@step, code)
        setup = null
        codes = account.newRecoveryCodes()
    }

    fun cancelTotp() = step { setup = null }

    fun disableTotp() = step { account.disableTotp() }

    /** New recovery codes, replacing the old ones. */
    fun newRecoveryCodes() = step { codes = account.newRecoveryCodes() }

    /** The recovery codes are saved somewhere: keep them. */
    fun keepRecoveryCodes() = step {
        account.keepRecoveryCodes(codes ?: return@step)
        codes = null
    }

    fun signOutDevice(id: String) = step { account.signOutDevice(id) }

    fun signOutOthers() = step { account.signOutOthers() }

    /** Sends a code to a new [email]; [confirmEmail] with it moves the account there. */
    fun changeEmail(email: String) = step {
        account.changeEmail(email)
        emailSent = email.trim()
    }

    fun confirmEmail(code: String) = step {
        account.confirmEmail(code)
        emailSent = null
        app.say(strings.done)
    }

    /** Links a Google or Apple account by an ID token from its own sign-in. */
    fun link(provider: String, idToken: String, nonce: String?) = step { account.link(provider, idToken, nonce) }

    fun unlink(provider: String) = step { account.unlink(provider) }

    /** Stops removing the second step someone asked to remove. */
    fun cancelReset() = step { account.cancelReset() }
}
