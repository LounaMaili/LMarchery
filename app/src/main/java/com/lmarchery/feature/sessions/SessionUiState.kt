package com.lmarchery.feature.sessions

import com.lmarchery.core.model.session.*
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
    val canEdit get() = ready && supportedConfiguration && session?.status == SessionStatus.IN_PROGRESS
    val canAddImpact get() = canEdit && editingEnd != null && displayedEnd?.uuid == editingEnd?.uuid && editingEnd!!.impacts.size < 3
    val canValidate get() = canEdit && editingEnd?.impacts?.size == 3 && displayedEnd?.uuid == editingEnd?.uuid
    val canNewEnd get() = canEdit && editingEnd == null
    val canComplete get() = canEdit && session!!.ends.none { it.status == EndStatus.EDITING && it.impacts.isNotEmpty() }
    val provisionalTotal get() = editingEnd?.let(::calculateEndScore) ?: 0
    val validatedTotal get() = session?.let(::summarizeSessionScore)?.totalScore ?: 0
}
