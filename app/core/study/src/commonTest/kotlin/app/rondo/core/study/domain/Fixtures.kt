package app.rondo.core.study.domain

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.uuid.Uuid

fun newId() = Uuid.generateV7().toString()

/** Runs [block], which never really suspends: common tests have no event loop. */
fun <T> blocking(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
}
