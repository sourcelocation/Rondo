package app.rondo.core.markup.domain

import java.text.Normalizer

internal actual fun decompose(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
