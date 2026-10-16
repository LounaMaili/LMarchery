package com.lmarchery.feature.sessions

import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.FinalScoreDecision
import com.lmarchery.core.model.scoring.BoundaryReference
import com.lmarchery.core.model.scoring.BoundaryValue
import com.lmarchery.core.scoring.archive.DomainArchive
import com.lmarchery.core.scoring.calculateEndScore
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles as WA
import com.lmarchery.core.scoring.summarizeSessionScore

enum class IndoorProfile(val label: String) {
    RECURVE_40("Classique 40 cm / 18 m"), COMPOUND_40("Poulies 40 cm / 18 m"),
    RECURVE_60("Classique 60 cm / 25 m"), COMPOUND_60("Poulies 60 cm / 25 m");
    val target get() = when (this) {
        RECURVE_40 -> WA.recurve40
        COMPOUND_40 -> WA.compound40
        RECURVE_60 -> WA.recurve60
        COMPOUND_60 -> WA.compound60
    }
}

enum class SessionScreen { LIST, CREATE, SESSION }
enum class ProblemKind { CONFLICT, INTERVENTION, TECHNICAL, NOT_FOUND, INVALID_ACTION }
data class UiProblem(val kind: ProblemKind, val message: String) {
    val retryable get() = kind == ProblemKind.TECHNICAL
}
data class SessionEntry(val uuid: String, val session: Session? = null, val problem: UiProblem? = null)
data class PendingSave(
    val description: String,
    val expected: DomainArchive?,
    val proposed: DomainArchive,
    val selectedEndUuid: String?,
)

enum class ImpactEditPhase { SELECTED, AWAITING_POINT, CANDIDATE, SAVING, CONFIRMED, FAILED }

/** One active operation; score candidates never carry a new position. */
sealed interface ImpactProposal {
    data object Selection : ImpactProposal
    data class Reposition(val point: NormalizedPoint? = null, val preview: FinalScoreDecision? = null,
        val reason: String = "") : ImpactProposal
    data class BoundaryJudgment(val boundary: BoundaryReference? = null, val selected: BoundaryValue? = null,
        val showOtherBoundaries: Boolean = false) : ImpactProposal
    data class ExceptionalCorrection(val selected: BoundaryValue? = null, val reason: String = "") : ImpactProposal
}

/** Presentation only: never substitutes for the confirmed archive or issues historical evidence. */
data class ImpactEdit(
    val sessionUuid: String,
    val endUuid: String,
    val impactUuid: String,
    val phase: ImpactEditPhase = ImpactEditPhase.SELECTED,
    val proposal: ImpactProposal = ImpactProposal.Selection,
) {
    val candidate get() = (proposal as? ImpactProposal.Reposition)?.point
    val preview get() = (proposal as? ImpactProposal.Reposition)?.preview
    val reason get() = when (proposal) {
        is ImpactProposal.Reposition -> proposal.reason
        is ImpactProposal.ExceptionalCorrection -> proposal.reason
        else -> ""
    }
}

data class SessionUiState(
    val screen: SessionScreen = SessionScreen.LIST,
    val busy: Boolean = false,
    val archiveOperationLocked: Boolean = false,
    val entries: List<SessionEntry> = emptyList(),
    val saved: DomainArchive? = null,
    val pending: PendingSave? = null,
    val problem: UiProblem? = null,
    val storedPreview: DomainArchive? = null,
    val selectedProfile: IndoorProfile = IndoorProfile.RECURVE_40,
    val title: String = "",
    val selectedEndUuid: String? = null,
    val confirmation: String? = null,
    val impactEdit: ImpactEdit? = null,
) {
    val session get() = saved?.sessions?.single()
    val editingEnd get() = session?.ends?.singleOrNull { it.status == EndStatus.EDITING }
    val displayedEnd get() = session?.ends?.find { it.uuid == selectedEndUuid } ?: editingEnd ?: session?.ends?.lastOrNull()
    val supportedConfiguration get() = session?.supportsSessionEditing() == true
    /** Compatibility read alias; import and export share one operation lock. */
    val exportLocked get() = archiveOperationLocked
    val ready get() = !busy && pending == null && !archiveOperationLocked
    val canNavigate get() = ready && impactEdit == null
    val canEdit get() = ready && screen == SessionScreen.SESSION && supportedConfiguration && session?.status == SessionStatus.IN_PROGRESS
    val isEditingDisplayed get() = editingEnd != null && displayedEnd?.uuid == editingEnd?.uuid
    val selectedImpact get() = impactEdit?.takeIf { it.sessionUuid == session?.uuid && it.endUuid == displayedEnd?.uuid }
        ?.let { selection -> displayedEnd?.impacts?.find { it.uuid == selection.impactUuid } }
    val canSelectImpact get() = canEdit && isEditingDisplayed &&
        (impactEdit == null || impactEdit.phase in listOf(ImpactEditPhase.SELECTED, ImpactEditPhase.CONFIRMED))
    val canBeginReposition get() = canSelectImpact && selectedImpact != null
    val canProposeReposition get() = canEdit && isEditingDisplayed && selectedImpact != null &&
        impactEdit?.proposal is ImpactProposal.Reposition &&
        impactEdit?.phase in listOf(ImpactEditPhase.AWAITING_POINT, ImpactEditPhase.CANDIDATE)
    val canConfirmReposition get() = canProposeReposition && impactEdit?.phase == ImpactEditPhase.CANDIDATE &&
        impactEdit.candidate != null && impactEdit.preview != null &&
        (selectedImpact?.finalScoreDecision?.provenance?.isManual != true || impactEdit.reason.isNotBlank())
    val canBeginScoreCorrection get() = canSelectImpact && selectedImpact != null
    val canProposeScoreDecision get() = canEdit && isEditingDisplayed && selectedImpact != null &&
        impactEdit?.phase == ImpactEditPhase.CANDIDATE &&
        (impactEdit.proposal is ImpactProposal.BoundaryJudgment || impactEdit.proposal is ImpactProposal.ExceptionalCorrection)
    val scoreOptions get() = selectedImpact?.let { scoreDecisionOptions(it.targetType, it.point) }
    val availableBoundaries get() = scoreOptions?.let { options ->
        if ((impactEdit?.proposal as? ImpactProposal.BoundaryJudgment)?.showOtherBoundaries == true)
            options.nearby + options.other else options.nearby
    }.orEmpty()
    val selectedBoundary get() = (impactEdit?.proposal as? ImpactProposal.BoundaryJudgment)?.boundary?.let { reference ->
        availableBoundaries.singleOrNull { it.reference == reference }
    }
    val proposedScore get() = when (val proposal = impactEdit?.proposal) {
        is ImpactProposal.BoundaryJudgment -> proposal.selected
        is ImpactProposal.ExceptionalCorrection -> proposal.selected
        else -> null
    }
    val canConfirmScoreDecision get() = canProposeScoreDecision && when (val proposal = impactEdit?.proposal) {
        is ImpactProposal.BoundaryJudgment -> selectedBoundary?.let {
            proposal.selected == it.inner || proposal.selected == it.outer
        } == true
        is ImpactProposal.ExceptionalCorrection -> proposal.selected in scoreOptions!!.legalValues && proposal.reason.isNotBlank()
        else -> false
    }
    val canAddImpact get() = canEdit && impactEdit == null && isEditingDisplayed && editingEnd!!.impacts.size < 3
    val canValidate get() = canEdit && impactEdit == null && editingEnd?.impacts?.size == 3 && isEditingDisplayed
    val canNewEnd get() = canEdit && impactEdit == null && editingEnd == null
    val canComplete get() = canEdit && impactEdit == null && session!!.ends.none { it.status == EndStatus.EDITING && it.impacts.isNotEmpty() }
    val provisionalTotal get() = editingEnd?.let(::calculateEndScore) ?: 0
    val validatedTotal get() = session?.let(::summarizeSessionScore)?.totalScore ?: 0
}
