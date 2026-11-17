package com.lmarchery.core.scoring.archive

import com.lmarchery.core.model.session.Session
import com.lmarchery.core.scoring.archive.v4.*
import com.lmarchery.core.scoring.configured.ConfiguredSession
import java.util.Collections

/** Complete editor revision, deliberately not an implicitly encodable V1 archive. */
interface SessionContent {
    val profiles: List<TargetProfileSnapshot>
    val sessions: List<Session>
    /** Compatibility only: throws when a V1 snapshot would lose terminal evidence. */
    fun toSnapshot(): ArchiveSnapshot = snapshotArchive(profiles, sessions)
}

fun SessionContent.sameContent(other: SessionContent?): Boolean = other != null && profiles == other.profiles && sessions == other.sessions

private class SessionRevision(profile: TargetProfileSnapshot, session: Session) : SessionContent {
    override val profiles = Collections.singletonList(profile)
    override val sessions = Collections.singletonList(session)
}

/** The exact edition is supplied, never inferred during a historical revision. */
fun validatedSessionContent(profile: TargetProfileSnapshot, session: Session): SessionContent {
    val configured = ConfiguredSession.fromValidatedLegacySession(session, profile)
    require(reconstructArchiveV4(snapshotArchiveV4(listOf(profile), listOf(configured), emptyList(), emptyList()))
        is ArchiveV4Reconstruction.Accepted) { "Complete session content is invalid." }
    return SessionRevision(profile, session)
}

fun SessionContent.configuredLegacyValue(): ConfiguredSession {
    require(sessions.size == 1 && profiles.size == 1) { "A complete single historical revision is required." }
    return ConfiguredSession.fromValidatedLegacySession(sessions.single(), profiles.single())
}

/** Explicit homogeneous value conversion only; the SQL representation is checked separately by its caller. */
fun ConfiguredSession.requireLegacyHomogeneousValue(): Session {
    require(initialConfigurationOrigin == com.lmarchery.core.scoring.configured.InitialConfigurationOrigin.LEGACY_DERIVED &&
        ends.all { it.configuration == initialConfiguration && it.origin == com.lmarchery.core.scoring.configured.EndConfigurationOrigin.LegacyDerived }) {
        "Only an exact homogeneous historical value can become Session."
    }
    val c = initialConfiguration; val m = metadata
    return Session(uuid = uuid, date = m.date, status = status, startedAt = startedAt, endedAt = endedAt,
        plannedEndCount = m.plannedEndCount, title = m.title, targetType = c.targetType, distanceM = c.distanceM,
        arrowCountMode = c.arrowCountMode, arrowCountPerEnd = c.arrowCountPerEnd, isIndoor = c.isIndoor,
        scoringMode = c.scoringMode, bowUuid = m.bowUuid, arrowSetUuid = m.arrowSetUuid,
        locationName = m.locationName, notes = m.notes, ends = ends.map { it.end }, createdAt = createdAt, updatedAt = updatedAt)
}
