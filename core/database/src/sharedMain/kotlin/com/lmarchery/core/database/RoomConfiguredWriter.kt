package com.lmarchery.core.database

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.lmarchery.core.scoring.archive.TargetProfileSnapshot
import com.lmarchery.core.scoring.archive.v3.*
import com.lmarchery.core.scoring.configured.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.CancellationException

/** No archive restore and no legacy projection. The whole database shares one writer lock. */
internal class RoomConfiguredWriter(private val database: SessionDatabase, private val ensureOpen: () -> Unit) {
    suspend fun write(request: ConfiguredWriteRequest): ConfiguredWriteResult {
        var writesAttempted = false
        fun outcome() = if (writesAttempted) ConfiguredCommitOutcome.UNCONFIRMED else ConfiguredCommitOutcome.NOT_ATTEMPTED
        try {
            val caller = currentCoroutineContext()
            caller.ensureActive(); ensureOpen()
            val source = request.proposed
            validateConfiguredSession(source)
            if (request is ConfiguredWriteRequest.Create && (source.initialConfigurationOrigin != InitialConfigurationOrigin.CREATION ||
                source.ends.any { it.origin == EndConfigurationOrigin.LegacyDerived }))
                return conflict(request, ConfiguredWriteConflict.INVALID_PROVENANCE, "initialConfigurationOrigin", "Normal creation requires domain creation provenance, not a historical restore.")
            if (request is ConfiguredWriteRequest.Update) try {
                validateConfiguredSessionUpdate(request.expected, source)
            } catch (error: IllegalArgumentException) {
                return conflict(request, ConfiguredWriteConflict.INVALID_TRANSITION,
                    (error as? ConfiguredSessionException)?.path ?: "transition", error.message ?: "Invalid configured transition.")
            }
            val profiles = (listOf(source.initialConfiguration) + source.ends.map { it.configuration })
                .map { TargetProfileSnapshot(it.targetType, it.regulatoryEdition) }.distinctBy { it.definition!!.uuid }
            val received = validateStoredV3(snapshotArchiveV3(profiles, listOf(source), emptyList(), emptyList()))
            val receivedRows = received.toSnapshot().sessions!!.single().configuredRows()
            return database.useWriterConnection { connection -> connection.immediateTransaction {
                caller.ensureActive(); ensureOpen()
                val queries = StrictStorageQueries(connection)
                val before = collectArchiveV3(queries, caller, ArchiveV3Limits())
                val previous = before.sessions.singleOrNull { it.uuid == source.uuid }
                if (previous == null && before.identitiesV3().containsKey(source.uuid))
                    return@immediateTransaction conflict(request, ConfiguredWriteConflict.IDENTITY_OCCUPIED,
                        "identities[${source.uuid}]", "Session UUID is already owned by another entity type.")
                if (previous != null && queries.sessionConfiguration(source.uuid)?.representation != CONFIGURED)
                    return@immediateTransaction conflict(request, ConfiguredWriteConflict.NOT_CONFIGURED, "sessions[${source.uuid}]", "A legacy session cannot be implicitly converted or overwritten.")
                if (previous == source) return@immediateTransaction ConfiguredWriteResult.AlreadyApplied(request)
                when (request) {
                    is ConfiguredWriteRequest.Create -> if (previous != null)
                        return@immediateTransaction conflict(request, ConfiguredWriteConflict.IDENTITY_OCCUPIED, "sessions[${source.uuid}]", "This session UUID already has different content or provenance.")
                    is ConfiguredWriteRequest.Update -> {
                        if (previous == null) return@immediateTransaction conflict(request, ConfiguredWriteConflict.NOT_FOUND, "sessions[${source.uuid}]", "Expected session is absent.")
                        if (previous != request.expected) return@immediateTransaction conflict(request, ConfiguredWriteConflict.REVISION, "sessions[${source.uuid}]", "Stored complete revision differs from expected; keep both candidates and reconcile explicitly.")
                    }
                }
                val knownProfiles = before.profiles.associateBy { it.definition!!.uuid }
                for (profile in profiles) if (knownProfiles[profile.definition!!.uuid]?.let { it != profile } == true)
                    return@immediateTransaction conflict(request, ConfiguredWriteConflict.IDENTITY_OCCUPIED, "profiles[${profile.definition!!.uuid}]", "Existing profile definition and edition are immutable.")
                val knownIds = before.identitiesV3()
                val owned = previous?.let { old -> buildSet {
                    add(old.uuid); old.ends.forEach { add(it.uuid); it.end.impacts.forEach { add(it.uuid) } }
                } }.orEmpty()
                for ((uuid, identity) in received.identitiesV3()) {
                    val known = knownIds[uuid] ?: continue
                    if (uuid !in owned && !(identity.kind == "PROFILE" && known == identity))
                        return@immediateTransaction conflict(request, ConfiguredWriteConflict.IDENTITY_OCCUPIED, "identities[$uuid]", "UUID already belongs to ${known.kind}; no adoption, overwrite or material repair.")
                }
                val additions = profiles.filter { it.definition!!.uuid !in knownProfiles }
                val expected = validateStoredV3(snapshotArchiveV3((before.profiles + additions).sortedBy { it.definition!!.uuid },
                    (before.sessions.filterNot { it.uuid == source.uuid } + source).sortedBy { it.uuid }, before.bows, before.arrowSets))
                val audit = ConfiguredWriteAudit(before, source)
                val untouched = audit.fingerprint(connection)
                val dao = database.records()
                // Set before the first DML attempt; any later technical error is conservatively unconfirmed.
                caller.ensureActive(); writesAttempted = true
                received.identitiesV3().values.filter { it.uuid !in knownIds }.forEach { caller.ensureActive(); dao.insertIdentity(it) }
                additions.forEach { caller.ensureActive(); dao.writeProfile(it) }
                val rows = receivedRows.physical
                if (previous == null) {
                    dao.insertSession(rows.session)
                    dao.insertSessionConfiguration(receivedRows.initial)
                    // All physical ends must exist before predecessor FK captures, irrespective of DTO order.
                    rows.ends.forEach { caller.ensureActive(); dao.insertEnd(it) }
                    receivedRows.captures.forEach { caller.ensureActive(); dao.insertEndConfiguration(it) }
                    rows.impacts.forEach { caller.ensureActive(); dao.insertImpact(it) }
                    dao.insertDecisions(rows.decisions); dao.insertRepositions(rows.repositions)
                } else {
                    val previousRows = before.toSnapshot().sessions!!.single { it.uuid == source.uuid }.configuredRows()
                    dao.updatePhysicalSessionRows(previousRows.physical, rows)
                    val oldEnds = previous.ends.map { it.uuid }.toSet()
                    receivedRows.captures.filter { it.endUuid !in oldEnds }.forEach { caller.ensureActive(); dao.insertEndConfiguration(it) }
                }
                caller.ensureActive()
                val after = collectArchiveV3(queries, caller, ArchiveV3Limits())
                dataCheck(after == expected, "configured.verification", "Complete persisted content differs from the exact proposal; abort the transaction.")
                dataCheck(audit.fingerprint(connection) == untouched, "configured.untouched", "Unrelated SQL rows or immutable captures changed; abort the transaction.")
                connection.usePrepared("PRAGMA foreign_key_check") { statement ->
                    dataCheck(!statement.step(), "configured.foreignKeys", "Foreign key integrity failed; abort the transaction.")
                }
                caller.ensureActive()
                when (request) {
                    is ConfiguredWriteRequest.Create -> ConfiguredWriteResult.Created(request)
                    is ConfiguredWriteRequest.Update -> ConfiguredWriteResult.Updated(request)
                }
            } }
        } catch (error: ConfiguredSessionException) {
            return if (writesAttempted) ConfiguredWriteResult.NeedsIntervention(request,
                ArchiveV3StorageDiagnostic(ArchiveV3StorageIssueKind.DOMAIN, error.path, requireNotNull(error.message)), outcome())
            else conflict(request, ConfiguredWriteConflict.INVALID_TRANSITION, error.path, requireNotNull(error.message))
        } catch (error: CorruptV3Storage) {
            return ConfiguredWriteResult.NeedsIntervention(request, error.diagnostic, outcome())
        } catch (error: CorruptStorage) {
            return ConfiguredWriteResult.NeedsIntervention(request, ArchiveV3StorageDiagnostic(ArchiveV3StorageIssueKind.STORAGE,
                error.diagnostic.path, error.diagnostic.detail, storageIssue = error.diagnostic), outcome())
        } catch (error: CancellationException) { throw ConfiguredWriteCancelled(request, outcome(), error) }
          catch (error: Exception) { return if (writesAttempted) ConfiguredWriteResult.CommitUnconfirmed(request, error)
              else ConfiguredWriteResult.TechnicalFailure(request, error) }
    }
}
private fun conflict(request: ConfiguredWriteRequest, kind: ConfiguredWriteConflict, path: String, detail: String) =
    ConfiguredWriteResult.Conflict(request, kind, StorageDiagnostic(path, detail))
