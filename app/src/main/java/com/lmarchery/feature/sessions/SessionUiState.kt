package com.lmarchery.feature.sessions

import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.FinalScoreDecision
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

/** Presentation only: never substitutes for the confirmed archive or issues historical evidence. */
data class ImpactEdit(
    val sessionUuid: String,
    val endUuid: String,
    val impactUuid: String,
    val phase: ImpactEditPhase = ImpactEditPhase.SELECTED,
    val candidate: NormalizedPoint? = null,
    val preview: FinalScoreDecision? = null,
    val reason: String = "",
)

data class SessionUiState(
    val screen: SessionScreen = SessionScreen.LIST,
    val busy: Boolean = false,
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
    val supportedConfiguration get() = session?.let { s ->
        IndoorProfile.entries.any { it.target == s.targetType } && s.arrowCountMode == ArrowCountMode.FIXED &&
            s.arrowCountPerEnd == 3 && s.scoringMode == ScoringMode.TRAINING &&
            s.ends.count { it.status == EndStatus.EDITING } <= 1 &&
            s.ends.all { end -> end.impacts.size <= 3 && end.impacts.map { it.index }.sorted() == (1..end.impacts.size).toList() }
    } == true
    val ready get() = !busy && pending == null
    val canNavigate get() = ready && impactEdit == null
    val canEdit get() = ready && screen == SessionScreen.SESSION && supportedConfiguration && session?.status == SessionStatus.IN_PROGRESS
    val isEditingDisplayed get() = editingEnd != null && displayedEnd?.uuid == editingEnd?.uuid
    val selectedImpact get() = impactEdit?.takeIf { it.sessionUuid == session?.uuid && it.endUuid == displayedEnd?.uuid }
        ?.let { selection -> displayedEnd?.impacts?.find { it.uuid == selection.impactUuid } }
    val canSelectImpact get() = canEdit && isEditingDisplayed &&
        (impactEdit == null || impactEdit.phase in listOf(ImpactEditPhase.SELECTED, ImpactEditPhase.CONFIRMED))
    val canBeginReposition get() = canSelectImpact && selectedImpact != null
    val canProposeReposition get() = canEdit && isEditingDisplayed && selectedImpact != null &&
        impactEdit?.phase in listOf(ImpactEditPhase.AWAITING_POINT, ImpactEditPhase.CANDIDATE)
    val canConfirmReposition get() = canProposeReposition && impactEdit?.phase == ImpactEditPhase.CANDIDATE &&
        impactEdit.candidate != null && impactEdit.preview != null &&
        (selectedImpact?.finalScoreDecision?.provenance?.isManual != true || impactEdit.reason.isNotBlank())
    val canAddImpact get() = canEdit && impactEdit == null && isEditingDisplayed && editingEnd!!.impacts.size < 3
    val canValidate get() = canEdit && impactEdit == null && editingEnd?.impacts?.size == 3 && isEditingDisplayed
    val canNewEnd get() = canEdit && impactEdit == null && editingEnd == null
    val canComplete get() = canEdit && impactEdit == null && session!!.ends.none { it.status == EndStatus.EDITING && it.impacts.isNotEmpty() }
    val provisionalTotal get() = editingEnd?.let(::calculateEndScore) ?: 0
    val validatedTotal get() = session?.let(::summarizeSessionScore)?.totalScore ?: 0
}
