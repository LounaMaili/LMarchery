package com.lmarchery.core.database

import androidx.room.*
import com.lmarchery.core.scoring.archive.v3.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.CancellationException

/** Archive append only: one reader preview and one writer confirmation, never a loop of public create calls. */
internal class RoomArchiveV3Import(private val database: SessionDatabase, private val ensureOpen: () -> Unit) {
    suspend fun preview(source: DomainArchiveV3): ArchiveV3PreviewResult = try {
        val caller = currentCoroutineContext()
        caller.ensureActive(); ensureOpen()
        val archive = validateStoredV3(source.toSnapshot())
        database.useReaderConnection { connection -> connection.deferredTransaction {
            val before = collectArchiveV3(StrictStorageQueries(connection), caller, ArchiveV3Limits())
            val fingerprint = fingerprintStorage(connection)
            val plan = planArchiveImportV3(archive, before)
            caller.ensureActive()
            ArchiveV3PreviewResult.Ready(ArchiveV3ImportPreview(archive, plan, before, fingerprint))
        } }
    } catch (error: CorruptV3Storage) { ArchiveV3PreviewResult.NeedsIntervention(error.diagnostic) }
      catch (error: CorruptStorage) { ArchiveV3PreviewResult.NeedsIntervention(error.v3Diagnostic()) }
      catch (error: CancellationException) { throw error }
      catch (error: Exception) { ArchiveV3PreviewResult.TechnicalFailure(error) }

    suspend fun restore(confirmed: ArchiveV3ImportPreview): ArchiveV3ImportResult {
        var writesAttempted = false
        fun outcome() = if (writesAttempted) ArchiveV3CommitOutcome.UNCONFIRMED else ArchiveV3CommitOutcome.NOT_ATTEMPTED
        try {
            val caller = currentCoroutineContext()
            caller.ensureActive(); ensureOpen()
            val source = validateStoredV3(confirmed.archive.toSnapshot())
            return database.useWriterConnection { connection -> connection.immediateTransaction {
                caller.ensureActive(); ensureOpen()
                val queries = StrictStorageQueries(connection)
                val before = collectArchiveV3(queries, caller, ArchiveV3Limits())
                val fingerprint = fingerprintStorage(connection)
                val plan = planArchiveImportV3(source, before)
                if (before != confirmed.baseline || fingerprint != confirmed.baselineFingerprint || plan != confirmed.plan || !plan.canImport)
                    return@immediateTransaction ArchiveV3ImportResult.Conflict(confirmed, StorageDiagnostic("importV3.preview",
                        "Exact local content, SQL representation or plan changed, or conflicts exist; explicitly preview again."))
                if (!plan.hasAdditions) {
                    caller.ensureActive()
                    return@immediateTransaction ArchiveV3ImportResult.AlreadyPresent(confirmed, plan.identicalCounts(), plan.isEmptyArchive)
                }
                val newProfiles = plan.profiles.newUuids.toSet()
                val newSessions = plan.sessions.newUuids.toSet()
                val newBows = plan.bows.newUuids.toSet()
                val newArrows = plan.arrowSets.newUuids.toSet()
                val profiles = source.profiles.filter { it.definition!!.uuid in newProfiles }
                val sessions = source.sessions.filter { it.uuid in newSessions }
                val bows = source.bows.filter { it.uuid in newBows }
                val arrows = source.arrowSets.filter { it.uuid in newArrows }
                // Validate the union and its cumulative budgets BEFORE the first INSERT.
                val expected = validateStoredV3(snapshotArchiveV3(
                    (before.profiles + profiles).sortedBy { it.definition!!.uuid }, (before.sessions + sessions).sortedBy { it.uuid },
                    (before.bows + bows).sortedBy { it.uuid }, (before.arrowSets + arrows).sortedBy { it.uuid }))
                val knownIds = before.identitiesV3()
                val newIds = source.identitiesV3().values.filter { it.uuid !in knownIds }
                val newEnds = sessions.flatMap { it.ends }.map { it.uuid }.toSet()
                val newImpacts = sessions.flatMap { it.ends.flatMap { e -> e.end.impacts.map { it.uuid } } }.toSet()
                val exclusions = mapOf(
                    "entity_identities" to ("uuid" to newIds.map { it.uuid }.toSet()),
                    "target_profiles" to ("uuid" to newProfiles), "target_zones" to ("profileUuid" to newProfiles),
                    "sessions" to ("uuid" to newSessions), "session_configurations" to ("sessionUuid" to newSessions),
                    "ends" to ("sessionUuid" to newSessions), "end_configurations" to ("endUuid" to newEnds),
                    "impacts" to ("endUuid" to newEnds), "score_decisions" to ("impactUuid" to newImpacts),
                    "impact_repositions" to ("impactUuid" to newImpacts),
                    "bows" to ("uuid" to newBows), "arrow_sets" to ("uuid" to newArrows),
                )
                caller.ensureActive()
                writesAttempted = true
                val dao = database.records()
                newIds.forEach { caller.ensureActive(); dao.insertIdentity(it) }
                profiles.forEach { caller.ensureActive(); dao.writeProfile(it) }
                source.toSnapshot().sessions!!.filter { it.uuid in newSessions }.forEach { snapshot ->
                    caller.ensureActive()
                    // Every new V3 root is CONFIGURED, even when legitimately LEGACY_DERIVED.
                    // Identical local legacy roots are skipped and retain their physical representation.
                    dao.insertConfiguredRows(snapshot.configuredRows(), caller)
                }
                bows.forEach { caller.ensureActive(); database.equipment().insertBow(it.row()) }
                arrows.forEach { caller.ensureActive(); database.equipment().insertArrowSet(it.row()) }
                // Never return a normal failure from this body after DML: THROW to roll back ALL additions.
                val after = collectArchiveV3(queries, caller, ArchiveV3Limits())
                dataCheck(after == expected, "importV3.verification", "Complete stored archive differs from source additions plus unchanged local data.")
                val preserved = fingerprintStorage(connection, exclusions)
                val changedTable = fingerprint.keys.firstOrNull { fingerprint[it] != preserved[it] }
                dataCheck(changedTable == null, "importV3.unchanged.$changedTable", "Existing raw SQL values changed; abort the entire restoration.")
                connection.usePrepared("PRAGMA foreign_key_check") { dataCheck(!it.step(), "importV3.foreignKeys", "Foreign key violation; abort restoration.") }
                caller.ensureActive()
                ArchiveV3ImportResult.Imported(confirmed, plan.addedCounts(), plan.identicalCounts())
            } }
        } catch (error: CorruptV3Storage) { return ArchiveV3ImportResult.NeedsIntervention(confirmed, error.diagnostic, outcome()) }
          catch (error: CorruptStorage) { return ArchiveV3ImportResult.NeedsIntervention(confirmed, error.v3Diagnostic(), outcome()) }
          catch (error: CancellationException) { throw ArchiveV3ImportCancelled(confirmed, outcome(), error) }
          catch (error: Exception) { return if (writesAttempted) ArchiveV3ImportResult.CommitUnconfirmed(confirmed, error)
              else ArchiveV3ImportResult.TechnicalFailure(confirmed, error) }
    }
}

private fun CorruptStorage.v3Diagnostic() = ArchiveV3StorageDiagnostic(ArchiveV3StorageIssueKind.STORAGE,
    diagnostic.path, diagnostic.detail, storageIssue = diagnostic)
private fun ArchiveV3ImportPlan.addedCounts() = ArchiveV3Counts(sessions.newUuids.size, profiles.newUuids.size,
    ends.newUuids.size, impacts.newUuids.size, bows.newUuids.size, arrowSets.newUuids.size)
private fun ArchiveV3ImportPlan.identicalCounts() = ArchiveV3Counts(sessions.identicalUuids.size, profiles.identicalUuids.size,
    ends.identicalUuids.size, impacts.identicalUuids.size, bows.identicalUuids.size, arrowSets.identicalUuids.size)
