package com.lmarchery.core.scoring.configured

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.ScoreDecisionRequest
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.archive.DomainArchive
import java.time.Instant
import java.util.Collections

/** Pure aggregate; not a persisted Session, archive DTO, or authorization to overwrite storage. */
class ConfiguredSession private constructor(private val state: State) {
    val uuid get() = state.uuid
    val metadata get() = state.metadata
    val initialConfiguration get() = state.initialConfiguration
    val initialConfigurationOrigin get() = state.initialConfigurationOrigin
    val status get() = state.status
    val startedAt get() = state.startedAt
    val endedAt get() = state.endedAt
    val createdAt get() = state.createdAt
    val updatedAt get() = state.updatedAt
    val ends: List<ConfiguredEnd> = Collections.unmodifiableList(state.ends.toList())

    init { validateConfiguredSession(this) }

    fun start(startedAt: Instant): ConfiguredSession {
        requireStatus(SessionStatus.DRAFT)
        requireEventTime(startedAt)
        return revise(state.copy(status = SessionStatus.IN_PROGRESS, startedAt = startedAt, updatedAt = startedAt))
    }

    fun createNextEnd(
        endUuid: String,
        createdAt: Instant,
        choice: NextEndConfigurationChoice = NextEndConfigurationChoice.Inherit,
        notes: String? = null,
    ): ConfiguredSession {
        requireStatus(SessionStatus.IN_PROGRESS)
        requireEventTime(createdAt)
        requireConfiguredUuid(endUuid, "ends.uuid")
        val previous = ends.maxByOrNull { it.end.index }
        val index = try { Math.addExact(previous?.end?.index ?: 0, 1) } catch (_: ArithmeticException) {
            throw ConfiguredSessionException(ConfiguredSessionIssue.INDEX_OVERFLOW, "ends.index", "No next Int index exists.")
        }
        val configuration = resolveNextEndConfiguration(initialConfiguration, previous?.configuration, choice)
        val origin = when (choice) {
            is NextEndConfigurationChoice.Replace -> EndConfigurationOrigin.Explicit
            NextEndConfigurationChoice.Inherit -> previous?.let { EndConfigurationOrigin.Inherited(it.uuid) }
                ?: EndConfigurationOrigin.Initial
        }
        val end = End(endUuid, index, EndStatus.EDITING, notes = notes, createdAt = createdAt, updatedAt = createdAt)
        val captured = ConfiguredEnd(uuid, end, configuration, origin)
        return revise(state.copy(ends = ends + captured, updatedAt = createdAt))
    }

    /** Only an existing end can be revised. Its owner, index, configuration and origin are retained. */
    fun withEnd(end: End, integratedAt: Instant = end.updatedAt): ConfiguredSession {
        requireStatus(SessionStatus.IN_PROGRESS)
        val previous = ownedEnd(end.uuid)
        validateConfiguredEndRevision(previous.end, end)
        requireConfigured(!integratedAt.isBefore(createdAt) && !integratedAt.isBefore(end.updatedAt),
            ConfiguredSessionIssue.TEMPORAL_CONFLICT, "updatedAt", "Integration must cover the child and session creation.")
        val next = ConfiguredEnd(uuid, end, previous.configuration, previous.origin)
        return revise(state.copy(ends = ends.map { if (it.uuid == end.uuid) next else it },
            updatedAt = maxOf(updatedAt, integratedAt)))
    }

    fun withImpact(endUuid: String, impact: ArrowImpact, integratedAt: Instant = impact.updatedAt): ConfiguredSession {
        requireStatus(SessionStatus.IN_PROGRESS)
        val previous = editableEnd(endUuid)
        previous.end.impacts.find { it.uuid == impact.uuid }?.let {
            requireConfigured(it.index == impact.index, ConfiguredSessionIssue.HISTORY_CONFLICT,
                "ends[$endUuid].impacts[${impact.uuid}].index", "An impact cannot change index.")
        }
        return withEnd(previous.end.withImpact(impact, integratedAt), integratedAt)
    }

    fun decideImpact(endUuid: String, impactUuid: String, request: ScoreDecisionRequest, changedAt: Instant): ConfiguredSession {
        val owner = editableEnd(endUuid)
        val impact = ownedImpact(owner, impactUuid)
        return withImpact(endUuid, impact.withFinalScore(owner.configuration.targetType, request, changedAt))
    }

    fun repositionImpact(endUuid: String, impactUuid: String, newPoint: NormalizedPoint,
        changedAt: Instant, reason: String? = null): ConfiguredSession {
        val owner = editableEnd(endUuid)
        return withImpact(endUuid, ownedImpact(owner, impactUuid).reposition(newPoint, changedAt, reason))
    }

    /** Delegates to today's End.validate: no new minimum/maximum arrow policy. */
    fun validateEnd(endUuid: String, validatedAt: Instant): ConfiguredSession =
        withEnd(editableEnd(endUuid).end.validate(validatedAt))

    /** Like the historical domain, closure freezes all ends, including any provisional ones. */
    fun complete(endedAt: Instant): ConfiguredSession {
        requireStatus(SessionStatus.IN_PROGRESS)
        requireEventTime(endedAt)
        return revise(state.copy(status = SessionStatus.COMPLETED, endedAt = endedAt, updatedAt = endedAt))
    }

    fun withMetadata(metadata: ConfiguredSessionMetadata, changedAt: Instant): ConfiguredSession {
        requireConfigured(status != SessionStatus.COMPLETED, ConfiguredSessionIssue.FORBIDDEN_TRANSITION,
            "status", "A completed aggregate is immutable.")
        requireEventTime(changedAt)
        return revise(state.copy(metadata = metadata, updatedAt = changedAt))
    }

    /** No arbitrary replacement parameters; changes go through explicit operations. */
    fun copy(): ConfiguredSession = ConfiguredSession(state.copy(ends = ends))

    private fun revise(next: State): ConfiguredSession = ConfiguredSession(next).also { validateConfiguredSessionUpdate(this, it) }
    private fun requireStatus(expected: SessionStatus) = requireConfigured(status == expected,
        ConfiguredSessionIssue.FORBIDDEN_TRANSITION, "status", "Expected $expected, got $status.")
    private fun requireEventTime(at: Instant) = requireConfigured(!at.isBefore(updatedAt),
        ConfiguredSessionIssue.TEMPORAL_CONFLICT, "updatedAt", "An event cannot predate the latest aggregate revision.")
    private fun ownedEnd(endUuid: String): ConfiguredEnd = ends.singleOrNull { it.uuid == endUuid }
        ?: throw ConfiguredSessionException(ConfiguredSessionIssue.UNKNOWN_END, "ends[$endUuid]", "End is not owned by this session.")
    private fun editableEnd(endUuid: String): ConfiguredEnd {
        requireStatus(SessionStatus.IN_PROGRESS)
        return ownedEnd(endUuid).also { requireConfigured(it.end.status == EndStatus.EDITING,
            ConfiguredSessionIssue.FORBIDDEN_TRANSITION, "ends[$endUuid].status", "A validated end cannot be edited.") }
    }
    private fun ownedImpact(owner: ConfiguredEnd, impactUuid: String): ArrowImpact =
        owner.end.impacts.singleOrNull { it.uuid == impactUuid } ?: throw ConfiguredSessionException(
            ConfiguredSessionIssue.UNKNOWN_IMPACT, "ends[${owner.uuid}].impacts[$impactUuid]", "Impact is not owned by this end.")

    override fun equals(other: Any?): Boolean = other is ConfiguredSession && state == other.state
    override fun hashCode(): Int = state.hashCode()

    companion object {
        fun create(uuid: String, metadata: ConfiguredSessionMetadata, initialConfiguration: EndConfiguration,
            createdAt: Instant): ConfiguredSession = ConfiguredSession(State(uuid, metadata, initialConfiguration,
            InitialConfigurationOrigin.CREATION, SessionStatus.DRAFT, null, null, emptyList(), createdAt, createdAt))

        /** Only a fully reconstructed archive supplies the original profile definition AND edition. */
        fun fromLegacyArchive(archive: DomainArchive, sessionUuid: String): ConfiguredSession {
            val session = archive.sessions.singleOrNull { it.uuid == sessionUuid } ?: throw ConfiguredSessionException(
                ConfiguredSessionIssue.MISSING_HISTORICAL_SOURCE, "sessions[$sessionUuid]", "A validated source session is required.")
            val profile = archive.profiles.singleOrNull { it.definition == session.targetType } ?: throw ConfiguredSessionException(
                ConfiguredSessionIssue.MISSING_HISTORICAL_SOURCE, "profiles", "The exact historical profile is required.")
            val configuration = EndConfiguration(session.targetType, profile.regulatoryEdition, session.distanceM,
                session.arrowCountMode, session.arrowCountPerEnd, session.isIndoor, session.scoringMode)
            val metadata = ConfiguredSessionMetadata(session.date, session.title, session.plannedEndCount,
                session.bowUuid, session.arrowSetUuid, session.locationName, session.notes)
            return ConfiguredSession(State(session.uuid, metadata, configuration, InitialConfigurationOrigin.LEGACY_DERIVED, session.status, session.startedAt,
                session.endedAt, session.ends.map { ConfiguredEnd(session.uuid, it, configuration, EndConfigurationOrigin.LegacyDerived) },
                session.createdAt, session.updatedAt))
        }
    }

    private data class State(val uuid: String, val metadata: ConfiguredSessionMetadata,
        val initialConfiguration: EndConfiguration, val initialConfigurationOrigin: InitialConfigurationOrigin, val status: SessionStatus, val startedAt: Instant?,
        val endedAt: Instant?, val ends: List<ConfiguredEnd>, val createdAt: Instant, val updatedAt: Instant)
}
