package com.lmarchery.core.scoring.configured

import com.lmarchery.core.model.session.End
import com.lmarchery.core.model.session.EndConfiguration
import com.lmarchery.core.model.session.EndStatus
import com.lmarchery.core.model.session.SessionStatus
import com.lmarchery.core.scoring.mixed.EndConfigurationBinding
import com.lmarchery.core.scoring.mixed.bindEndConfigurations
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import java.util.UUID

/** Snapshot consistency is not permission to replace an existing revision. */
fun validateConfiguredSession(session: ConfiguredSession) {
    val identities = mutableMapOf<String, String>()
    fun claim(uuid: String, path: String) {
        requireConfiguredUuid(uuid, path)
        requireConfigured(identities.putIfAbsent(uuid, path) == null, ConfiguredSessionIssue.IDENTITY_COLLISION,
            path, "Identity already belongs to ${identities[uuid]}.")
    }
    claim(session.uuid, "uuid")
    val profiles = mutableMapOf<String, EndConfiguration>()
    fun profile(configuration: EndConfiguration) {
        val target = configuration.targetType
        val previous = profiles.putIfAbsent(target.uuid, configuration)
        if (previous == null) claim(target.uuid, "profiles[${target.uuid}]")
        else requireConfigured(previous.targetType == target && previous.regulatoryEdition == configuration.regulatoryEdition,
            ConfiguredSessionIssue.CONFIGURATION_CONFLICT, "profiles[${target.uuid}]", "One identity requires one definition and edition.")
        val known = BuiltInTargetRegistry.find(target.uuid)
        requireConfigured(if (known != null) known.definition == target && known.regulatoryEdition == configuration.regulatoryEdition
            else !target.isBuiltIn, ConfiguredSessionIssue.CONFIGURATION_CONFLICT,
            "profiles[${target.uuid}]", "The published definition and edition must be exact; no inferred edition.")
    }
    profile(session.initialConfiguration)
    val ordered = session.ends.sortedBy { it.end.index }
    requireConfigured(ordered.map { it.end.index }.distinct().size == ordered.size,
        ConfiguredSessionIssue.HISTORY_CONFLICT, "ends.index", "End indexes must be unique within their session.")
    ordered.forEachIndexed { index, captured ->
        val end = captured.end
        requireConfigured(captured.sessionUuid == session.uuid, ConfiguredSessionIssue.WRONG_OWNER,
            "ends[${end.uuid}].sessionUuid", "An end belongs to exactly one session.")
        claim(end.uuid, "ends[${end.uuid}]")
        profile(captured.configuration)
        end.impacts.forEach { claim(it.uuid, "ends[${end.uuid}].impacts[${it.uuid}]") }
        requireConfigured(!end.createdAt.isBefore(session.createdAt) && !end.updatedAt.isAfter(session.updatedAt),
            ConfiguredSessionIssue.TEMPORAL_CONFLICT, "ends[${end.uuid}]", "Parent timestamps must cover the child.")
        when (val origin = captured.origin) {
            EndConfigurationOrigin.Initial -> requireConfigured(index == 0 && captured.configuration == session.initialConfiguration,
                ConfiguredSessionIssue.CONFIGURATION_CONFLICT, "ends[${end.uuid}].origin", "Initial capture requires the first end and initial configuration.")
            is EndConfigurationOrigin.Inherited -> requireConfigured(index > 0 && origin.previousEndUuid == ordered[index - 1].uuid &&
                captured.configuration == ordered[index - 1].configuration, ConfiguredSessionIssue.CONFIGURATION_CONFLICT,
                "ends[${end.uuid}].origin", "Inheritance must name the immediately preceding end and preserve its complete value.")
            EndConfigurationOrigin.Explicit -> Unit
            EndConfigurationOrigin.LegacyDerived -> requireConfigured(captured.configuration == session.initialConfiguration &&
                session.initialConfigurationOrigin == InitialConfigurationOrigin.LEGACY_DERIVED,
                ConfiguredSessionIssue.CONFIGURATION_CONFLICT, "ends[${end.uuid}].origin", "A legacy homogeneous source must match its initial configuration.")
        }
    }
    bindEndConfigurations(session.ends.map { it.end }, session.ends.map { EndConfigurationBinding(it.uuid, it.configuration) })
    requireConfigured(!session.updatedAt.isBefore(session.createdAt), ConfiguredSessionIssue.TEMPORAL_CONFLICT,
        "updatedAt", "Session revision cannot predate creation.")
    val start = session.startedAt; val end = session.endedAt
    val validStatus = when (session.status) {
        SessionStatus.DRAFT -> start == null && end == null
        SessionStatus.IN_PROGRESS -> start != null && end == null
        SessionStatus.COMPLETED -> start != null && end != null
    }
    requireConfigured(validStatus, ConfiguredSessionIssue.FORBIDDEN_TRANSITION, "status", "Status and event dates must agree.")
    start?.let { requireConfigured(!it.isBefore(session.createdAt) && !it.isAfter(session.updatedAt),
        ConfiguredSessionIssue.TEMPORAL_CONFLICT, "startedAt", "Invalid start chronology.") }
    end?.let { requireConfigured(start != null && !it.isBefore(start) && !it.isAfter(session.updatedAt) &&
        session.ends.all { child -> !child.end.updatedAt.isAfter(it) }, ConfiguredSessionIssue.TEMPORAL_CONFLICT,
        "endedAt", "Closure must cover start and every child modification.") }
}

/** Same complete-state revision boundary required by future stores; no deletion or reparenting. */
fun validateConfiguredSessionUpdate(expected: ConfiguredSession, proposed: ConfiguredSession) {
    validateConfiguredSession(proposed)
    requireConfigured(proposed.uuid == expected.uuid && proposed.createdAt == expected.createdAt,
        ConfiguredSessionIssue.HISTORY_CONFLICT, "uuid/createdAt", "Session identity and creation must be preserved.")
    requireConfigured(proposed.initialConfiguration == expected.initialConfiguration && proposed.initialConfigurationOrigin == expected.initialConfigurationOrigin,
        ConfiguredSessionIssue.CONFIGURATION_CONFLICT,
        "initialConfiguration", "The initial capture cannot be replaced, even before an end has impacts.")
    requireConfigured(!proposed.updatedAt.isBefore(expected.updatedAt), ConfiguredSessionIssue.TEMPORAL_CONFLICT,
        "updatedAt", "A revision cannot be older than expected.")
    if (expected.status == SessionStatus.COMPLETED) {
        requireConfigured(proposed == expected, ConfiguredSessionIssue.FORBIDDEN_TRANSITION, "status", "A completed aggregate is frozen.")
        return
    }
    requireConfigured(expected.status != SessionStatus.IN_PROGRESS || proposed.status != SessionStatus.DRAFT,
        ConfiguredSessionIssue.FORBIDDEN_TRANSITION, "status", "An active session cannot return to draft.")
    if (expected.startedAt != null) requireConfigured(proposed.startedAt == expected.startedAt,
        ConfiguredSessionIssue.HISTORY_CONFLICT, "startedAt", "An existing start must be retained.")
    else proposed.startedAt?.let { requireConfigured(!it.isBefore(expected.updatedAt), ConfiguredSessionIssue.TEMPORAL_CONFLICT,
        "startedAt", "A new start must cover the previous revision.") }
    proposed.endedAt?.let { requireConfigured(!it.isBefore(expected.updatedAt), ConfiguredSessionIssue.TEMPORAL_CONFLICT,
        "endedAt", "Closure cannot be backdated.") }
    val next = proposed.ends.associateBy { it.uuid }
    expected.ends.forEach { old ->
        val revised = next[old.uuid] ?: throw ConfiguredSessionException(ConfiguredSessionIssue.HISTORY_CONFLICT,
            "ends[${old.uuid}]", "No existing end can be removed.")
        requireConfigured(revised.sessionUuid == old.sessionUuid && revised.configuration == old.configuration && revised.origin == old.origin,
            ConfiguredSessionIssue.CONFIGURATION_CONFLICT, "ends[${old.uuid}].configuration", "Owner, configuration and capture origin are immutable.")
        validateConfiguredEndRevision(old.end, revised.end)
    }
    val existing = expected.ends.map { it.uuid }.toSet()
    val added = proposed.ends.filter { it.uuid !in existing }.sortedBy { it.end.index }
    var lastIndex = expected.ends.maxOfOrNull { it.end.index } ?: 0
    added.forEach { captured ->
        requireConfigured(proposed.status != SessionStatus.DRAFT && captured.origin != EndConfigurationOrigin.LegacyDerived,
            ConfiguredSessionIssue.FORBIDDEN_TRANSITION, "ends[${captured.uuid}]", "New ends require an active creation, not invented legacy provenance.")
        requireConfigured(captured.end.index.toLong() == lastIndex.toLong() + 1L && !captured.end.createdAt.isBefore(expected.updatedAt),
            ConfiguredSessionIssue.HISTORY_CONFLICT, "ends[${captured.uuid}]", "New ends must append, without backdating or renumbering.")
        lastIndex = captured.end.index
    }
}

internal fun validateConfiguredEndRevision(previous: End, proposed: End) {
    val path = "ends[${previous.uuid}]"
    requireConfigured(proposed.uuid == previous.uuid && proposed.index == previous.index,
        ConfiguredSessionIssue.HISTORY_CONFLICT, path, "End UUID and index must be preserved.")
    if (previous.status == EndStatus.VALIDATED) requireConfigured(previous == proposed,
        ConfiguredSessionIssue.FORBIDDEN_TRANSITION, path, "A validated end is frozen.")
    val next = proposed.impacts.associateBy { it.uuid }
    previous.impacts.forEach { old ->
        val revised = next[old.uuid] ?: throw ConfiguredSessionException(ConfiguredSessionIssue.HISTORY_CONFLICT,
            "$path.impacts[${old.uuid}]", "Impacts cannot be removed or moved to a different end.")
        requireConfigured(revised.index == old.index, ConfiguredSessionIssue.HISTORY_CONFLICT,
            "$path.impacts[${old.uuid}].index", "An impact index must be preserved.")
    }
    // Delegate temporal, score-decision and reposition-prefix checks to the historical domain API.
    previous.copy(status = proposed.status, impacts = proposed.impacts, notes = proposed.notes,
        createdAt = proposed.createdAt, updatedAt = proposed.updatedAt, validatedAt = proposed.validatedAt)
}

internal fun requireConfiguredUuid(uuid: String, path: String) {
    val parsed = runCatching { UUID.fromString(uuid) }.getOrNull()
    requireConfigured(parsed != null && parsed.toString() == uuid && parsed.version() == 4 && parsed.variant() == 2,
        ConfiguredSessionIssue.INVALID_IDENTITY, path, "A canonical RFC UUID v4 is required.")
}
