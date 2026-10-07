package app.rondo.core

import java.text.Normalizer

internal actual fun decompose(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
