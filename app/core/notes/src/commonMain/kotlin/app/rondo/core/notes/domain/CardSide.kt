package app.rondo.core.notes.domain

import app.rondo.core.markup.domain.TextBlock
import kotlin.js.JsExport

/** One thing drawn on a card side. [kind]: text, image, audio, occlusion, divider, label or typein. */
@JsExport
class Piece internal constructor(
    val kind: String,
    val size: String = "normal",
    val center: Boolean = true,
    val blocks: Array<TextBlock> = emptyArray(),
    val media: String? = null,
    val masks: Array<Mask> = emptyArray(),
    /** A label's text, or the expected answer of a type-in. */
    val text: String? = null,
    /** Plain text to speak: the field is in the deck's language. */
    val speech: String? = null,
    /** The field is in the deck's language (its spelling and voice), not the learner's. */
    val foreign: Boolean = false,
)

/** An occlusion mask, in fractions of the image. */
@JsExport
class Mask internal constructor(
    val group: Int,
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val active: Boolean,
    val hidden: Boolean,
)

/** A laid-out side of a card: pieces for Rondo templates, HTML for Anki 1:1 templates. */
@JsExport
class CardSide internal constructor(val pieces: Array<Piece>, val html: String? = null, val css: String? = null)
