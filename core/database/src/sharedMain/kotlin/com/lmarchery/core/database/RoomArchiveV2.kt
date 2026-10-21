package com.lmarchery.core.database

import androidx.room.deferredTransaction
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import com.lmarchery.core.scoring.archive.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.CancellationException
import kotlin.coroutines.CoroutineContext

/** Transaction-local collection; never calls public store/catalog methods that would start independent transactions. */
internal class RoomArchiveV2(private val database: SessionDatabase, private val ensureOpen: () -> Unit) {
    suspend fun read(): ArchiveV2ReadResult = guarded(
        { ArchiveV2ReadResult.NeedsIntervention(it) }, { ArchiveV2ReadResult.TechnicalFailure(it) },
    ) {
        val caller = currentCoroutineContext()
        caller.ensureActive(); ensureOpen()
        database.useReaderConnection { it.deferredTransaction {
            val archive = collect(caller)
            caller.ensureActive()
            ArchiveV2ReadResult.Found(archive)
        } }
    }

    suspend fun preview(source: DomainArchiveV2): ArchiveV2PreviewResult = guarded(
        { ArchiveV2PreviewResult.NeedsIntervention(it) }, { ArchiveV2PreviewResult.TechnicalFailure(it) },
    ) {
        val caller = currentCoroutineContext()
        caller.ensureActive(); ensureOpen()
        val archive = validated(source.toSnapshot())
        database.useReaderConnection { it.deferredTransaction {
            val known = collect(caller)
            val plan = planArchiveImportV2(archive, known)
            caller.ensureActive()
            ArchiveV2PreviewResult.Ready(ArchiveV2ImportPreview(archive, plan, known))
        } }
    }

    suspend fun import(confirmed: ArchiveV2ImportPreview): ArchiveV2ImportResult = guarded(
        { ArchiveV2ImportResult.NeedsIntervention(it) }, { ArchiveV2ImportResult.TechnicalFailure(it) },
    ) {
        val caller = currentCoroutineContext()
        caller.ensureActive(); ensureOpen()
        val source = validated(confirmed.archive.toSnapshot())
        database.useWriterConnection { it.immediateTransaction {
            caller.ensureActive(); ensureOpen()
            val before = collect(caller)
            val plan = planArchiveImportV2(source, before)
            if (before != confirmed.baseline || plan != confirmed.plan || !plan.canImport) {
                ArchiveV2ImportResult.Conflict(StorageDiagnostic("importV2.preview",
                    "Local state or plan changed, or contains conflicts; preview again explicitly."))
            } else if (!plan.hasAdditions) {
                caller.ensureActive()
                ArchiveV2ImportResult.AlreadyPresent(plan.identicalCounts(), plan.isEmptyArchive)
            } else {
                val added = subset(source, plan)
                val dao = database.records()
                caller.ensureActive()
                added.identities().forEach { caller.ensureActive(); dao.insertIdentity(it) }
                added.profiles.forEach { caller.ensureActive(); dao.writeProfile(it) }
                // The row's archiveSchemaVersion remains 1: this is the unchanged session representation,
                // not the version of the complete JSON envelope.
                source.toSnapshot().sessions!!.filter { it.uuid in plan.sessions.newUuids }.forEach { session ->
                    caller.ensureActive()
                    dao.writeSession(ArchiveSnapshot(ArchiveContractVersions.SCHEMA, ArchiveContractVersions.SCORING,
                        source.profiles, listOf(session)))
                }
                added.bows.forEach { caller.ensureActive(); database.equipment().insertBow(it.row()) }
                added.arrowSets.forEach { caller.ensureActive(); database.equipment().insertArrowSet(it.row()) }
                // THROW on every failure after INSERT, out of immediateTransaction, to roll back the whole archive.
                val after = collect(caller)
                val expected = validated(snapshotArchiveV2(
                    (before.profiles + added.profiles).sortedBy { it.definition!!.uuid },
                    (before.sessions + added.sessions).sortedBy { it.uuid },
                    (before.bows + added.bows).sortedBy { it.uuid },
                    (before.arrowSets + added.arrowSets).sortedBy { it.uuid }))
                dataCheck(after == expected, "importV2.verification",
                    "Complete stored state differs from source plus unchanged local data; transaction rolled back.")
                caller.ensureActive()
                ArchiveV2ImportResult.Imported(plan.addedCounts(), plan.identicalCounts())
            }
        } }
    }

    /** Includes every physical profile, even unreferenced ones; a stored root is never silently omitted. */
    private suspend fun collect(caller: CoroutineContext): DomainArchiveV2 {
        val dao = database.records()
        dao.requireLegacyArchive()
        caller.ensureActive()
        val registered = dao.allIdentities()
        val physical = dao.allRowIdentities()
        val byUuid = physical.groupBy { it.uuid }
        val duplicate = byUuid.entries.firstOrNull { it.value.size != 1 }
        dataCheck(duplicate == null, "identities[${duplicate?.key}]", "UUID is claimed by multiple physical entity types.")
        val registry = registered.associateBy { it.uuid }
        val mismatch = (registered.map { it.uuid } + physical.map { it.uuid }).sorted().firstOrNull { id ->
            registry[id] != byUuid[id]?.singleOrNull()
        }
        dataCheck(mismatch == null, "identities[$mismatch]", "Registry and physical entity ownership differ or a row is missing.")
        val orphan = dao.orphanChildPaths().firstOrNull()
        dataCheck(orphan == null, orphan ?: "children", "Stored child has no physical parent.")
        val profiles = dao.profileUuids().map { caller.ensureActive(); dao.profileSnapshot(it) }
        val sessions = dao.sessionUuids().map { uuid ->
            caller.ensureActive()
            val row = dao.session(uuid) ?: throw CorruptStorage(StorageDiagnostic("sessions[$uuid]", "Session row is missing."))
            when (val result = reconstructArchive(dao.sessionSnapshot(row))) {
                is ArchiveReconstruction.Accepted -> result.archive.sessions.single()
                is ArchiveReconstruction.NeedsIntervention -> throw CorruptStorage(StorageDiagnostic(
                    "sessions[$uuid].${result.issue.path}", result.issue.detail, result.issue))
            }
        }
        val equipment = database.equipment()
        val bows = equipment.bowUuids().map { uuid ->
            caller.ensureActive()
            equipment.bow(uuid)?.domain() ?: throw CorruptStorage(StorageDiagnostic("bows[$uuid]", "Bow row is missing."))
        }
        val arrows = equipment.arrowSetUuids().map { uuid ->
            caller.ensureActive()
            equipment.arrowSet(uuid)?.domain() ?: throw CorruptStorage(StorageDiagnostic("arrow_sets[$uuid]", "Arrow set row is missing."))
        }
        val result = validated(snapshotArchiveV2(profiles, sessions, bows, arrows))
        val reachable = result.identities().associateBy { it.uuid }
        val unreachable = physical.firstOrNull { reachable[it.uuid] != it }
        dataCheck(reachable.size == physical.size && unreachable == null, "identities[${unreachable?.uuid}]",
            "Stored entities include an unreachable descendant; archive would be incomplete.")
        caller.ensureActive()
        return result
    }
}

private fun validated(snapshot: ArchiveV2Snapshot): DomainArchiveV2 = when (val result = reconstructArchiveV2(snapshot)) {
    is ArchiveV2Reconstruction.Accepted -> result.archive
    is ArchiveV2Reconstruction.NeedsIntervention -> throw CorruptStorage(StorageDiagnostic(result.issue.path, result.issue.detail, result.issue))
}

internal fun DomainArchiveV2.identities(): List<IdentityRow> = buildList {
    profiles.forEach { add(IdentityRow(it.definition!!.uuid, "PROFILE")) }
    sessions.forEach { session ->
        add(IdentityRow(session.uuid, "SESSION"))
        session.ends.forEach { end ->
            add(IdentityRow(end.uuid, "END"))
            end.impacts.forEach { add(IdentityRow(it.uuid, "IMPACT")) }
        }
    }
    bows.forEach { add(IdentityRow(it.uuid, "BOW")) }
    arrowSets.forEach { add(IdentityRow(it.uuid, "ARROW_SET")) }
}

// A subset may refer to an already stored profile; it is a write batch, deliberately NOT a DomainArchiveV2.
private data class V2InsertBatch(
    val profiles: List<TargetProfileSnapshot>, val sessions: List<com.lmarchery.core.model.session.Session>,
    val bows: List<com.lmarchery.core.model.equipment.Bow>, val arrowSets: List<com.lmarchery.core.model.equipment.ArrowSet>,
) {
    fun identities(): List<IdentityRow> = buildList {
        profiles.forEach { add(IdentityRow(it.definition!!.uuid, "PROFILE")) }
        sessions.forEach { s -> add(IdentityRow(s.uuid, "SESSION")); s.ends.forEach { e ->
            add(IdentityRow(e.uuid, "END")); e.impacts.forEach { add(IdentityRow(it.uuid, "IMPACT")) }
        } }
        bows.forEach { add(IdentityRow(it.uuid, "BOW")) }; arrowSets.forEach { add(IdentityRow(it.uuid, "ARROW_SET")) }
    }
}
private fun subset(source: DomainArchiveV2, plan: ArchiveV2ImportPlan) = V2InsertBatch(
    source.profiles.filter { it.definition!!.uuid in plan.profiles.newUuids },
    source.sessions.filter { it.uuid in plan.sessions.newUuids }, source.bows.filter { it.uuid in plan.bows.newUuids },
    source.arrowSets.filter { it.uuid in plan.arrowSets.newUuids })
private fun ArchiveV2ImportPlan.addedCounts() = ArchiveV2Counts(sessions.newUuids.size, profiles.newUuids.size, bows.newUuids.size, arrowSets.newUuids.size)
private fun ArchiveV2ImportPlan.identicalCounts() = ArchiveV2Counts(sessions.identicalUuids.size, profiles.identicalUuids.size, bows.identicalUuids.size, arrowSets.identicalUuids.size)
private suspend inline fun <T> guarded(corruption: (StorageDiagnostic) -> T, failure: (Exception) -> T, block: () -> T): T =
    try { block() } catch (error: CorruptStorage) { corruption(error.diagnostic) }
    catch (error: CancellationException) { throw error }
    catch (error: Exception) { failure(error) }
