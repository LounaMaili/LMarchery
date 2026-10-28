package com.lmarchery.core.scoring.archive.v3

import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.model.time.UtcTimestamps
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.configured.*
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

enum class ArchiveV3IssueCode {
    MISSING_FIELD, UNSUPPORTED_VERSION, INVALID_UUID, DUPLICATE_UUID, UNKNOWN_PROFILE,
    PROFILE_IDENTITY_CONFLICT, MISSING_PROVENANCE, TEMPORAL_CONTEXT, CIVIL_CONTEXT_CONFLICT,
    INVALID_DOMAIN_VALUE, CONTRADICTORY_DERIVED_VALUE, HISTORY_CONFLICT, DIVERGENT_REVISION,
    CONFIGURATION_CONFLICT, WRONG_OWNER, INHERITANCE_CONFLICT, LIMIT,
}

data class ArchiveV3Issue(val code: ArchiveV3IssueCode, val path: String, val detail: String)
sealed interface ArchiveV3Reconstruction {
    data class Accepted(val archive: DomainArchiveV3) : ArchiveV3Reconstruction
    data class NeedsIntervention(val source: ArchiveV3Snapshot, val issue: ArchiveV3Issue) : ArchiveV3Reconstruction
}

/** Whole independent reconstruction, optionally compared conservatively with known complete entities.
 * No normal forward revision, conflict resolution, deletion or import is authorized by this function. */
fun reconstructArchiveV3(snapshot: ArchiveV3Snapshot, known: DomainArchiveV3? = null,
    limits: ArchiveV3Limits = ArchiveV3Limits()): ArchiveV3Reconstruction = try {
    if (v3Required(snapshot.schemaVersion, "schemaVersion") != ArchiveV3Contract.SCHEMA)
        v3Fail(ArchiveV3IssueCode.UNSUPPORTED_VERSION, "schemaVersion", "Only archive schema 3 is supported here.")
    if (v3Required(snapshot.scoringContractVersion, "scoringContractVersion") != ArchiveV3Contract.SCORING)
        v3Fail(ArchiveV3IssueCode.UNSUPPORTED_VERSION, "scoringContractVersion", "Only scoring contract 1 is supported.")
    validateV3Budgets(snapshot, limits)
    val shared = when (val result = reconstructArchiveV2(ArchiveV2Snapshot(ArchiveContractVersions.SCHEMA_V2,
        snapshot.scoringContractVersion, snapshot.profiles, emptyList(), snapshot.bows, snapshot.arrowSets))) {
        is ArchiveV2Reconstruction.Accepted -> result.archive
        is ArchiveV2Reconstruction.NeedsIntervention -> v3HistoricalIssue(result.issue)
    }
    val reconstructor = V3Reconstructor(shared)
    val sessions = v3Required(snapshot.sessions, "sessions").toList().mapIndexed { i, s -> reconstructor.session(s, "sessions[$i]") }
    val archive = DomainArchiveV3(shared.profiles, sessions, shared.bows, shared.arrowSets)
    if (known != null) compareKnownV3(known, archive)
    ArchiveV3Reconstruction.Accepted(archive)
} catch (error: InvalidV3) { ArchiveV3Reconstruction.NeedsIntervention(snapshot, error.issue) }

private class InvalidV3(val issue: ArchiveV3Issue) : RuntimeException(issue.detail)
internal fun v3Fail(code: ArchiveV3IssueCode, path: String, detail: String): Nothing = throw InvalidV3(ArchiveV3Issue(code, path, detail))
internal fun v3HistoricalIssue(issue: ArchiveIssue): Nothing = v3Fail(ArchiveV3IssueCode.valueOf(issue.code.name), issue.path, issue.detail)
private fun <T : Any> v3Required(value: T?, path: String): T = value ?: v3Fail(ArchiveV3IssueCode.MISSING_FIELD, path, "Required value is absent.")
private inline fun <T> checked(path: String, code: ArchiveV3IssueCode = ArchiveV3IssueCode.INVALID_DOMAIN_VALUE, block: () -> T): T =
    try { block() }
    catch (error: IllegalArgumentException) { v3Fail(code, path, error.message ?: "Invalid domain value.") }
    catch (error: DateTimeException) { v3Fail(code, path, error.message ?: "Invalid temporal value.") }
private fun time(value: String?, path: String) = checked(path, ArchiveV3IssueCode.TEMPORAL_CONTEXT) { UtcTimestamps.parse(v3Required(value, path)) }
private fun uuid(value: String?, path: String): String = v3Required(value, path).also { text ->
    checked(path, ArchiveV3IssueCode.INVALID_UUID) {
        val id = UUID.fromString(text)
        require(id.toString() == text && id.version() == 4 && id.variant() == 2) { "Canonical lowercase RFC UUID v4 required." }
    }
}

private class V3Reconstructor(shared: DomainArchiveV2) {
    private val profiles = shared.profiles.associateBy { it.definition!!.uuid }
    private val identities = (profiles.keys + shared.bows.map { it.uuid } + shared.arrowSets.map { it.uuid }).toMutableSet()
    private fun identity(value: String?, path: String): String = uuid(value, path).also {
        if (!identities.add(it)) v3Fail(ArchiveV3IssueCode.DUPLICATE_UUID, path, "Identity is already defined by another entity.")
    }

    private fun configuration(value: EndConfigurationSnapshot?, path: String): EndConfiguration {
        val c = v3Required(value, path)
        val id = uuid(c.targetTypeUuid, "$path.targetTypeUuid")
        val profile = profiles[id] ?: v3Fail(ArchiveV3IssueCode.UNKNOWN_PROFILE, "$path.targetTypeUuid", "Complete profile is absent from this archive.")
        if (c.regulatoryEdition != profile.regulatoryEdition) v3Fail(ArchiveV3IssueCode.PROFILE_IDENTITY_CONFLICT,
            "$path.regulatoryEdition", "Configuration and stored profile editions must be identical.")
        val distance = v3Required(c.distanceM, "$path.distanceM")
        val countMode = v3Required(c.arrowCountMode, "$path.arrowCountMode")
        validateEndConfiguration(distance, countMode, c.arrowCountPerEnd, c.regulatoryEdition).firstOrNull()?.let { issue ->
            val field = when (issue.field) {
                EndConfigurationField.DISTANCE_M -> "distanceM"
                EndConfigurationField.ARROW_COUNT_MODE -> "arrowCountMode"
                EndConfigurationField.ARROW_COUNT_PER_END -> "arrowCountPerEnd"
                else -> "regulatoryEdition"
            }
            v3Fail(ArchiveV3IssueCode.CONFIGURATION_CONFLICT, "$path.$field", issue.code.name)
        }
        return checked(path, ArchiveV3IssueCode.CONFIGURATION_CONFLICT) { EndConfiguration(profile.definition!!,
            c.regulatoryEdition, distance, countMode, c.arrowCountPerEnd, v3Required(c.isIndoor, "$path.isIndoor"),
            v3Required(c.scoringMode, "$path.scoringMode")) }
    }

    private fun origin(value: EndConfigurationOriginSnapshot?, path: String): EndConfigurationOrigin {
        val o = value ?: v3Fail(ArchiveV3IssueCode.MISSING_PROVENANCE, path, "Capture provenance is required.")
        val kind = o.kind ?: v3Fail(ArchiveV3IssueCode.MISSING_PROVENANCE, "$path.kind", "Capture kind is required.")
        if (kind != EndConfigurationOriginKind.INHERITED && o.previousEndUuid != null)
            v3Fail(ArchiveV3IssueCode.INHERITANCE_CONFLICT, "$path.previousEndUuid", "Only inheritance can name a previous end.")
        return when (kind) {
            EndConfigurationOriginKind.INITIAL -> EndConfigurationOrigin.Initial
            EndConfigurationOriginKind.EXPLICIT -> EndConfigurationOrigin.Explicit
            EndConfigurationOriginKind.LEGACY_DERIVED -> EndConfigurationOrigin.LegacyDerived
            EndConfigurationOriginKind.INHERITED -> EndConfigurationOrigin.Inherited(uuid(o.previousEndUuid, "$path.previousEndUuid"))
        }
    }

    fun session(value: ConfiguredSessionSnapshot, path: String): ConfiguredSession {
        val id = identity(value.uuid, "$path.uuid")
        val m = v3Required(value.metadata, "$path.metadata")
        val civil = v3Required(m.date, "$path.metadata.date")
        val date = checked("$path.metadata.date", ArchiveV3IssueCode.CIVIL_CONTEXT_CONFLICT) {
            val offset = v3Required(civil.offset, "$path.metadata.date.offset")
            require(!Regex("-00(?::?00){0,2}").matches(offset)) { "Unknown civil offset." }
            SessionDateTime.of(LocalDateTime.parse(v3Required(civil.localDateTime, "$path.metadata.date.localDateTime")),
                ZoneId.of(v3Required(civil.zoneId, "$path.metadata.date.zoneId")), ZoneOffset.of(offset))
        }
        m.bowUuid?.let { uuid(it, "$path.metadata.bowUuid") }; m.arrowSetUuid?.let { uuid(it, "$path.metadata.arrowSetUuid") }
        val title = v3Required(m.title, "$path.metadata.title")
        if (title.isBlank()) v3Fail(ArchiveV3IssueCode.INVALID_DOMAIN_VALUE, "$path.metadata.title", "Title must not be blank.")
        if (m.plannedEndCount != null && m.plannedEndCount <= 0) v3Fail(ArchiveV3IssueCode.INVALID_DOMAIN_VALUE,
            "$path.metadata.plannedEndCount", "Planned count must be positive or absent.")
        val metadata = checked("$path.metadata") { ConfiguredSessionMetadata(date, title,
            m.plannedEndCount, m.bowUuid, m.arrowSetUuid, m.locationName, m.notes) }
        val initial = configuration(value.initialConfiguration, "$path.initialConfiguration")
        val initialOrigin = value.initialConfigurationOrigin ?: v3Fail(ArchiveV3IssueCode.MISSING_PROVENANCE,
            "$path.initialConfigurationOrigin", "Initial provenance must be explicit.")
        val status = v3Required(value.status, "$path.status")
        val created = time(value.createdAt, "$path.createdAt"); val updated = time(value.updatedAt, "$path.updatedAt")
        if (updated.isBefore(created)) v3Fail(ArchiveV3IssueCode.TEMPORAL_CONTEXT, "$path.updatedAt", "Revision predates creation.")
        val started = value.startedAt?.let { time(it, "$path.startedAt") }; val ended = value.endedAt?.let { time(it, "$path.endedAt") }
        if ((status == SessionStatus.DRAFT && (started != null || ended != null)) ||
            (status == SessionStatus.IN_PROGRESS && (started == null || ended != null)) ||
            (status == SessionStatus.COMPLETED && (started == null || ended == null)))
            v3Fail(ArchiveV3IssueCode.TEMPORAL_CONTEXT, "$path.status", "Status and start/closure must agree.")
        if (started != null && (started.isBefore(created) || started.isAfter(updated)))
            v3Fail(ArchiveV3IssueCode.TEMPORAL_CONTEXT, "$path.startedAt", "Start is outside the session chronology.")
        if (ended != null && (ended.isBefore(started) || ended.isAfter(updated)))
            v3Fail(ArchiveV3IssueCode.TEMPORAL_CONTEXT, "$path.endedAt", "Closure is outside the session chronology.")
        val seenIndexes = mutableSetOf<Int>()
        val ends = v3Required(value.ends, "$path.ends").toList().mapIndexed { i, c ->
            val e = "$path.ends[$i]"
            if (uuid(c.sessionUuid, "$e.sessionUuid") != id) v3Fail(ArchiveV3IssueCode.WRONG_OWNER, "$e.sessionUuid", "Foreign session owner.")
            val raw = v3Required(c.end, "$e.end")
            identity(raw.uuid, "$e.end.uuid")
            if (raw.index <= 0) v3Fail(ArchiveV3IssueCode.INVALID_DOMAIN_VALUE, "$e.end.index", "Positive end index required.")
            if (!seenIndexes.add(raw.index)) v3Fail(ArchiveV3IssueCode.INVALID_DOMAIN_VALUE, "$e.end.index", "Repeated end index in this session.")
            val config = configuration(c.configuration, "$e.configuration")
            val impactIndexes = mutableSetOf<Int>()
            raw.impacts.forEachIndexed { j, impact ->
                val a = "$e.end.impacts[$j]"
                identity(impact.uuid, "$a.uuid")
                if (impact.index <= 0 || !impactIndexes.add(impact.index)) v3Fail(ArchiveV3IssueCode.INVALID_DOMAIN_VALUE,
                    "$a.index", "Impact indexes must be positive and unique within an end.")
                if (impact.targetTypeUuid != config.targetType.uuid) v3Fail(ArchiveV3IssueCode.PROFILE_IDENTITY_CONFLICT,
                    "$a.targetTypeUuid", "Impact must use this end's exact effective profile.")
            }
            val end = reconstructArchiveEnd(raw, config.targetType, "$e.end", ::v3HistoricalIssue)
            if (end.createdAt.isBefore(created)) v3Fail(ArchiveV3IssueCode.TEMPORAL_CONTEXT, "$e.end.createdAt", "End predates session creation.")
            if (end.updatedAt.isAfter(updated)) v3Fail(ArchiveV3IssueCode.TEMPORAL_CONTEXT, "$path.updatedAt", "Parent does not cover this end.")
            if (ended != null && end.updatedAt.isAfter(ended)) v3Fail(ArchiveV3IssueCode.TEMPORAL_CONTEXT, "$path.endedAt", "Closure does not cover this end.")
            val captureOrigin = origin(c.origin, "$e.origin")
            if (captureOrigin != EndConfigurationOrigin.LegacyDerived && (status == SessionStatus.DRAFT || started == null))
                v3Fail(ArchiveV3IssueCode.TEMPORAL_CONTEXT, "$e.origin", "A new capture requires an actual session start.")
            if (captureOrigin != EndConfigurationOrigin.LegacyDerived && started != null && end.createdAt.isBefore(started))
                v3Fail(ArchiveV3IssueCode.TEMPORAL_CONTEXT, "$e.end.createdAt", "A new capture cannot predate the session start.")
            ConfiguredEnd(id, end, config, captureOrigin)
        }
        // Validate the chain using indexes, while preserving the source's list order and identities.
        val ordered = ends.withIndex().sortedBy { it.value.end.index }
        ordered.forEachIndexed { i, indexed ->
            val captured = indexed.value; val p = "$path.ends[${indexed.index}]"
            when (val o = captured.origin) {
                EndConfigurationOrigin.Initial -> if (i != 0 || captured.configuration != initial)
                    v3Fail(ArchiveV3IssueCode.INHERITANCE_CONFLICT, "$p.origin", "Initial capture must match the initial value on the first end.")
                is EndConfigurationOrigin.Inherited -> if (i == 0 || o.previousEndUuid != ordered[i - 1].value.uuid ||
                    captured.configuration != ordered[i - 1].value.configuration)
                    v3Fail(ArchiveV3IssueCode.INHERITANCE_CONFLICT, "$p.origin", "Inheritance must match the immediately preceding end and its complete configuration.")
                EndConfigurationOrigin.LegacyDerived -> if (initialOrigin != InitialConfigurationOrigin.LEGACY_DERIVED || captured.configuration != initial)
                    v3Fail(ArchiveV3IssueCode.CONFIGURATION_CONFLICT, "$p.origin", "Historical derivation must match the homogeneous source initial value.")
                EndConfigurationOrigin.Explicit -> Unit
            }
        }
        return checked(path) { ConfiguredSession.rehydrate(id, metadata, initial, initialOrigin, status, started, ended, ends, created, updated) }
    }
}
