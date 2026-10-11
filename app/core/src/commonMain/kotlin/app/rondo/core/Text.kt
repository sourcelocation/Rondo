package app.rondo.core

/** Unicode canonical decomposition (NFD), as each platform does it. */
internal expect fun decompose(s: String): String
