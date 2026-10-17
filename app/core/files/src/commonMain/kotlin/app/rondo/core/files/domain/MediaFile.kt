package app.rondo.core.files.domain

/** A picture or sound: named by the SHA-256 of its [bytes], in hex. */
class MediaFile(val hash: String, val mime: String, val bytes: ByteArray)
