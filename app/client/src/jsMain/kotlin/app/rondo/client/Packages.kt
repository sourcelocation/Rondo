@file:JsModule("fflate")

package app.rondo.client

import org.khronos.webgl.Uint8Array

/** fflate: unzips off the main thread. */
internal external fun unzip(data: Uint8Array, callback: (error: dynamic, files: dynamic) -> Unit): dynamic
