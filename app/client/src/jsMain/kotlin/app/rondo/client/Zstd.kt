@file:JsModule("fzstd")

package app.rondo.client

import org.khronos.webgl.Uint8Array

internal external fun decompress(data: Uint8Array): Uint8Array
