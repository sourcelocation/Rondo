package app.rondo.core

internal actual fun decompose(s: String): String = s.asDynamic().normalize("NFD") as String
