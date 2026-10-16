package com.lmarchery.core.model.equipment

import java.time.Instant

/** Descriptive optional strings are retained verbatim, including their user-supplied units. */
class ArrowSet(
    override val uuid: String,
    override val name: String,
    val spine: String? = null,
    val length: String? = null,
    val pointWeight: String? = null,
    val nock: String? = null,
    val vanes: String? = null,
    val material: String? = null,
    val notes: String? = null,
    override val createdAt: Instant,
    override val updatedAt: Instant,
) : Equipment {
    init {
        validateEquipment(this)
    }

    /** Explicit immutable revision; UUID, creation and monotone time are checked against this value. */
    fun copy(
        uuid: String = this.uuid,
        name: String = this.name,
        spine: String? = this.spine,
        length: String? = this.length,
        pointWeight: String? = this.pointWeight,
        nock: String? = this.nock,
        vanes: String? = this.vanes,
        material: String? = this.material,
        notes: String? = this.notes,
        createdAt: Instant = this.createdAt,
        updatedAt: Instant = this.updatedAt,
    ): ArrowSet = ArrowSet(uuid, name, spine, length, pointWeight, nock, vanes, material, notes, createdAt, updatedAt).also { validateEquipmentUpdate(this, it) }

    private val snapshot = Snapshot(uuid, name, spine, length, pointWeight, nock, vanes, material, notes, createdAt, updatedAt)
    override fun equals(other: Any?): Boolean = other is ArrowSet && snapshot == other.snapshot
    override fun hashCode(): Int = snapshot.hashCode()
    override fun toString(): String = snapshot.toString().replaceFirst("Snapshot(", "ArrowSet(")

    private data class Snapshot(
        val uuid: String,
        val name: String,
        val spine: String?,
        val length: String?,
        val pointWeight: String?,
        val nock: String?,
        val vanes: String?,
        val material: String?,
        val notes: String?,
        val createdAt: Instant,
        val updatedAt: Instant,
    )
}
