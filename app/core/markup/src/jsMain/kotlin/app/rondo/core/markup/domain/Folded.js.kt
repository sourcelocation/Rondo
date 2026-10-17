package app.rondo.core.markup.domain

internal actual fun decompose(s: String): String = s.asDynamic().normalize("NFD") as String
