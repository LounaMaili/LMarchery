package com.lmarchery.core.model.session

import java.time.Instant

enum class ImpactCancellationReason { SAISIE_PAR_ERREUR }

/** A terminal participation decision. The original impact, judgment and traces remain untouched. */
class ImpactCancellation private constructor(
    val endUuid: String,
    val impactUuid: String,
    val cancelledAt: Instant,
    val reason: ImpactCancellationReason,
    val comment: String?,
) {
    override fun equals(other: Any?): Boolean = other is ImpactCancellation &&
        endUuid == other.endUuid && impactUuid == other.impactUuid && cancelledAt == other.cancelledAt &&
        reason == other.reason && comment == other.comment
    override fun hashCode(): Int = arrayOf(endUuid, impactUuid, cancelledAt, reason, comment).contentHashCode()

    companion object {
        /** Validated evidence reconstruction is not permission to revise a stored aggregate. */
        fun forImpact(endUuid: String, impact: ArrowImpact, cancelledAt: Instant,
            reason: ImpactCancellationReason = ImpactCancellationReason.SAISIE_PAR_ERREUR,
            comment: String? = null): ImpactCancellation {
            require(endUuid.isNotBlank()) { "Cancellation requires its end UUID." }
            require(!cancelledAt.isBefore(impact.updatedAt)) { "Cancellation cannot predate the impact revision." }
            return ImpactCancellation(endUuid, impact.uuid, cancelledAt, reason, comment)
        }
    }
}
