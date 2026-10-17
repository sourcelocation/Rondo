package app.rondo.core.notes.infrastructure

import app.rondo.core.kernel.infrastructure.Record
import app.rondo.core.notes.domain.AnkiCardDef
import app.rondo.core.notes.domain.AnkiTemplate
import app.rondo.core.notes.domain.Block
import app.rondo.core.notes.domain.CardDef
import app.rondo.core.notes.domain.FieldDef
import app.rondo.core.notes.domain.RondoTemplate
import app.rondo.core.notes.domain.Template
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A note type: [kind] 1 is Rondo's, 2 Anki's HTML kept 1:1; [def] its fields and cards. */
@Serializable
@SerialName("template")
class TemplateRecord(
    override val id: String,
    @SerialName("owner_id") val ownerId: String? = null,
    val name: String,
    val kind: Int,
    val def: Def,
    override val v: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
) : Record() {
    @Serializable
    class Def(val fields: List<FieldRecord>, val cards: List<CardRecord>, val css: String? = null)

    @Serializable
    class FieldRecord(val id: Int, val name: String, val kind: String, val tts: Boolean? = null)

    /** A card: Rondo's blocks, or Anki's HTML ([ankiFront], [ankiBack]). */
    @Serializable
    class CardRecord(
        val id: Int,
        val name: String,
        val front: List<BlockRecord> = emptyList(),
        val back: List<BlockRecord> = emptyList(),
        val each: Int? = null,
        val requires: List<List<Int>>? = null,
        @SerialName("anki_front") val ankiFront: String? = null,
        @SerialName("anki_back") val ankiBack: String? = null,
    )

    /** Exactly one of field, divider, label or typein. */
    @Serializable
    class BlockRecord(
        val field: Int? = null,
        val size: String? = null,
        val align: String? = null,
        val divider: Boolean? = null,
        val label: String? = null,
        val typein: Int? = null,
    )

    constructor(t: Template) : this(
        t.id,
        t.ownerId,
        t.name,
        if (t is AnkiTemplate) ANKI else RONDO,
        Def(
            t.fields.map { FieldRecord(it.id, it.name, it.kind.value, it.tts.takeIf { on -> on }) },
            when (t) {
                is RondoTemplate -> t.cards.map { c ->
                    CardRecord(c.id, c.name, c.front.map(::block), c.back.map(::block), c.each, c.requires)
                }

                is AnkiTemplate -> t.cards.map {
                    CardRecord(it.id, it.name, ankiFront = it.frontHtml, ankiBack = it.backHtml)
                }
            },
            (t as? AnkiTemplate)?.css,
        ),
        t.v,
        t.deletedAt,
    )

    /** The note type it stores; null when it isn't one this app knows how to read. */
    fun template(): Template? {
        val fields = def.fields.map { f ->
            val kind = FieldDef.Kind.entries.firstOrNull { it.value == f.kind } ?: return null
            FieldDef(f.id, f.name, kind, f.tts == true)
        }
        return when (kind) {
            RONDO -> RondoTemplate(
                id,
                name,
                fields,
                def.cards.map { c ->
                    CardDef(
                        c.id,
                        c.name,
                        c.front.map {
                            it.block() ?: return null
                        },
                        c.back.map { it.block() ?: return null },
                        c.each,
                        c.requires,
                    )
                },
                v,
                ownerId,
                deletedAt,
            )

            ANKI -> AnkiTemplate(
                id,
                name,
                fields,
                def.cards.map { AnkiCardDef(it.id, it.name, it.ankiFront ?: return null, it.ankiBack ?: return null) },
                def.css.orEmpty(),
                v,
                ownerId,
                deletedAt,
            )

            else -> null
        }
    }

    companion object {
        const val RONDO = 1
        const val ANKI = 2

        private fun block(b: Block) = when (b) {
            is Block.Field -> BlockRecord(field = b.id, size = b.size?.value, align = b.align?.value)
            Block.Divider -> BlockRecord(divider = true)
            is Block.Label -> BlockRecord(label = b.text, size = b.size?.value, align = b.align?.value)
            is Block.TypeIn -> BlockRecord(typein = b.id)
        }

        private fun BlockRecord.block(): Block? {
            val size = size?.let { s -> Block.Size.entries.firstOrNull { it.value == s } }
            val align = align?.let { a -> Block.Align.entries.firstOrNull { it.value == a } }
            return when (listOfNotNull(field, typein, divider?.takeIf { it }, label).size) {
                1 -> when {
                    field != null -> Block.Field(field, size, align)
                    typein != null -> Block.TypeIn(typein)
                    label != null -> Block.Label(label, size, align)
                    else -> Block.Divider
                }

                else -> null
            }
        }
    }
}
