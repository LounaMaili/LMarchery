package com.lmarchery.core.model.equipment

import java.time.Instant

/** Descriptive optional strings are retained verbatim, including their user-supplied units. */
class Bow(
    override val uuid: String,
    override val name: String,
    val type: String? = null,
    val drawWeightLbs: Float? = null,
    val brand: String? = null,
    val model: String? = null,
    val notes: String? = null,
    override val createdAt: Instant,
    override val updatedAt: Instant,
) : Equipment {
    init {
        validateEquipment(this)
        require(drawWeightLbs == null || drawWeightLbs.isFinite() && drawWeightLbs > 0f) {
            "Bow drawWeightLbs must be finite and strictly positive when provided."
        }
    }

    /** Explicit immutable revision; UUID, creation and monotone time are checked against this value. */
    fun copy(
        uuid: String = this.uuid,
        name: String = this.name,
        type: String? = this.type,
        drawWeightLbs: Float? = this.drawWeightLbs,
        brand: String? = this.brand,
        model: String? = this.model,
        notes: String? = this.notes,
        createdAt: Instant = this.createdAt,
        updatedAt: Instant = this.updatedAt,
    ): Bow = Bow(uuid, name, type, drawWeightLbs, brand, model, notes, createdAt, updatedAt).also { validateEquipmentUpdate(this, it) }

    private val snapshot = Snapshot(uuid, name, type, drawWeightLbs, brand, model, notes, createdAt, updatedAt)
    override fun equals(other: Any?): Boolean = other is Bow && snapshot == other.snapshot
    override fun hashCode(): Int = snapshot.hashCode()
    override fun toString(): String = snapshot.toString().replaceFirst("Snapshot(", "Bow(")

    private data class Snapshot(
        val uuid: String,
        val name: String,
        val type: String?,
        val drawWeightLbs: Float?,
        val brand: String?,
        val model: String?,
        val notes: String?,
        val createdAt: Instant,
        val updatedAt: Instant,
    )
}
