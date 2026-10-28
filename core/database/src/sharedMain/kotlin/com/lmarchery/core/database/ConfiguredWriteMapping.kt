package com.lmarchery.core.database

import kotlinx.coroutines.ensureActive
import com.lmarchery.core.model.time.UtcTimestamps
import com.lmarchery.core.scoring.archive.v3.*

internal data class ConfiguredRows(val physical: SessionRows, val initial: SessionConfigurationRow,
    val captures: List<EndConfigurationRow>)

/** Only V3 DTOs and relational rows. Never a homogeneous Session/SessionSnapshot intermediate. */
internal fun ConfiguredSessionSnapshot.configuredRows(): ConfiguredRows {
    val uuid = requireNotNull(uuid)
    val m = requireNotNull(metadata)
    val date = requireNotNull(m.date)
    val c = requireNotNull(initialConfiguration)
    val children = requireNotNull(ends)
    val row = SessionRow(uuid, requireNotNull(c.targetTypeUuid), 3, 1,
        requireNotNull(date.localDateTime), requireNotNull(date.zoneId), requireNotNull(date.offset), requireNotNull(status).name,
        startedAt?.let(::configuredTime), endedAt?.let(::configuredTime), m.plannedEndCount, requireNotNull(m.title),
        requireNotNull(c.distanceM), requireNotNull(c.arrowCountMode).name, c.arrowCountPerEnd, requireNotNull(c.isIndoor).flag(),
        requireNotNull(c.scoringMode).name, m.bowUuid, m.arrowSetUuid, m.locationName, m.notes,
        configuredTime(requireNotNull(createdAt)), configuredTime(requireNotNull(updatedAt)), children.size)
    val initial = SessionConfigurationRow(uuid, CONFIGURED, requireNotNull(initialConfigurationOrigin).name, c.stored())
    val captures = children.map { captured ->
        val origin = requireNotNull(captured.origin)
        EndConfigurationRow(requireNotNull(requireNotNull(captured.end).uuid), requireNotNull(captured.sessionUuid), requireNotNull(origin.kind).name,
            origin.previousEndUuid, requireNotNull(captured.configuration).stored())
    }
    return ConfiguredRows(physicalSessionRows(row, children.map { requireNotNull(it.end) }), initial, captures)
}
private fun configuredTime(text: String): SqlInstant = UtcTimestamps.parse(text).let { SqlInstant(it.epochSecond, it.nano) }
private fun EndConfigurationSnapshot.stored() = StoredEndConfiguration(requireNotNull(targetTypeUuid), regulatoryEdition,
    requireNotNull(distanceM), requireNotNull(arrowCountMode).name, arrowCountPerEnd, requireNotNull(isIndoor).flag(), requireNotNull(scoringMode).name)

private fun Boolean.flag(): Int = if (this) 1 else 0

/** One caller-owned transaction, with physical predecessor rows before inherited captures. */
internal suspend fun SessionDao.insertConfiguredRows(rows: ConfiguredRows, caller: kotlin.coroutines.CoroutineContext) {
    caller.ensureActive()
    insertSession(rows.physical.session)
    insertSessionConfiguration(rows.initial)
    rows.physical.ends.forEach { caller.ensureActive(); insertEnd(it) }
    rows.captures.forEach { caller.ensureActive(); insertEndConfiguration(it) }
    rows.physical.impacts.forEach { caller.ensureActive(); insertImpact(it) }
    caller.ensureActive(); insertDecisions(rows.physical.decisions)
    caller.ensureActive(); insertRepositions(rows.physical.repositions)
}
