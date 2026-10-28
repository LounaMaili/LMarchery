package com.lmarchery.feature.configured

import com.lmarchery.core.database.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.configured.ConfiguredSession
import com.lmarchery.feature.sessions.*

/** Presentation epoch rejects callbacks from an older revision/form/selection, including the same end UUID. */
data class ConfiguredTargetContext(val owner: AimContext, val epoch: Long)
enum class ConfiguredProblemKind { VALIDATION, IDENTITY_OCCUPIED, REVISION, NOT_FOUND, NOT_CONFIGURED,
    INVALID_TRANSITION, INVALID_PROVENANCE, INTERVENTION, TECHNICAL, UNCONFIRMED }
data class ConfiguredProblem(val kind: ConfiguredProblemKind, val message: String)
enum class ConfiguredReconciliation { ABSENT, EXPECTED, THIRD_STATE }

data class ConfiguredWriteContext(val selectedEndUuid: String?, val form: ConfiguredForm?, val edit: ImpactEdit?,
    val position: PositionCandidate?, val target: ConfiguredTargetContext?)
data class PendingConfiguredWrite(val description: String, val request: ConfiguredWriteRequest, val context: ConfiguredWriteContext)

/** Separate from SessionUiState/PendingSave. Only saved is acknowledged durable content. */
data class ConfiguredEditorState(
    val open: Boolean = false, val busy: Boolean = false, val saved: ConfiguredSession? = null,
    val form: ConfiguredForm? = null, val pending: PendingConfiguredWrite? = null,
    val lastResult: ConfiguredWriteResult? = null, val problem: ConfiguredProblem? = null,
    val compared: ConfiguredSession? = null, val reconciliation: ConfiguredReconciliation? = null,
    val confirmation: String? = null, val selectedEndUuid: String? = null,
    val equipmentChoices: EquipmentChoices = EquipmentChoices(),
    val edit: ImpactEdit? = null, val precision: Boolean = false,
    val position: PositionCandidate? = null, val activeAim: ActiveAim? = null, val epoch: Long = 0,
) {
    val selectedEnd get() = saved?.ends?.singleOrNull { it.uuid == selectedEndUuid }
    val ready get() = open && !busy && pending == null
    val mutable get() = ready && saved != null && saved.status != SessionStatus.COMPLETED
    val sessionActive get() = mutable && saved!!.status == SessionStatus.IN_PROGRESS
    val hasProposal get() = edit != null || position != null || activeAim != null
    val canNavigate get() = ready && form == null && !hasProposal
    val canForm get() = mutable && form == null && !hasProposal
    val editableEnd get() = sessionActive && form == null && selectedEnd?.end?.status == EndStatus.EDITING
    val targetContext get() = saved?.let { s -> selectedEnd?.let { e -> ConfiguredTargetContext(AimContext(s.uuid, e.uuid, edit?.impactUuid), epoch) } }
    val selectedImpact get() = edit?.takeIf { it.sessionUuid == saved?.uuid && it.endUuid == selectedEnd?.uuid }
        ?.let { e -> selectedEnd?.end?.impacts?.singleOrNull { it.uuid == e.impactUuid } }
    val hasCapacity get() = selectedEnd?.let { e -> e.configuration.arrowCountMode == ArrowCountMode.FREE ||
        e.end.impacts.size < e.configuration.arrowCountPerEnd!! } == true
    val canPlace get() = editableEnd && edit == null && hasCapacity
    val canAdd get() = canPlace && !precision && position == null && activeAim == null
    val canAim get() = precision && (canPlace || canReposition)
    val canConfirmPosition get() = canPlace && precision && position != null && activeAim == null
    val canSelectImpact get() = editableEnd && position == null && activeAim == null &&
        (edit == null || edit.phase == ImpactEditPhase.SELECTED || edit.phase == ImpactEditPhase.CONFIRMED)
    val canReposition get() = editableEnd && selectedImpact != null && edit?.proposal is ImpactProposal.Reposition &&
        edit.phase in listOf(ImpactEditPhase.AWAITING_POINT, ImpactEditPhase.CANDIDATE)
    val canConfirmReposition get() = canReposition && activeAim == null && edit?.candidate != null &&
        (!selectedImpact!!.finalScoreDecision.provenance.isManual || edit!!.reason.isNotBlank())
    val canScoreDecision get() = editableEnd && selectedImpact != null && edit?.phase == ImpactEditPhase.CANDIDATE &&
        (edit.proposal is ImpactProposal.BoundaryJudgment || edit.proposal is ImpactProposal.ExceptionalCorrection)
    val scoreOptions get() = selectedImpact?.let { scoreDecisionOptions(selectedEnd!!.configuration.targetType, it.point) }
    val selectedBoundary get() = (edit?.proposal as? ImpactProposal.BoundaryJudgment)?.let { p ->
        val options = scoreOptions ?: return@let null
        (options.nearby + if (p.showOtherBoundaries) options.other else emptyList()).singleOrNull { it.reference == p.boundary }
    }
    val proposedScore get() = when (val p = edit?.proposal) {
        is ImpactProposal.BoundaryJudgment -> p.selected
        is ImpactProposal.ExceptionalCorrection -> p.selected
        else -> null
    }
    val canConfirmScore get() = canScoreDecision && when (val p = edit?.proposal) {
        is ImpactProposal.BoundaryJudgment -> selectedBoundary?.let { p.selected == it.inner || p.selected == it.outer } == true
        is ImpactProposal.ExceptionalCorrection -> p.selected in scoreOptions!!.legalValues && p.reason.isNotBlank()
        else -> false
    }
    val canRetryExact get() = !busy && pending != null && reconciliation in listOf(ConfiguredReconciliation.ABSENT, ConfiguredReconciliation.EXPECTED)
}
