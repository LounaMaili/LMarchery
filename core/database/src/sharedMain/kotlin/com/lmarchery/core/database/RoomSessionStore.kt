package com.lmarchery.core.database

import androidx.room.immediateTransaction
import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import com.lmarchery.core.model.session.Session
import com.lmarchery.core.model.session.validateSessionUpdate
import com.lmarchery.core.scoring.archive.ArchiveIssue
import com.lmarchery.core.scoring.archive.ArchiveReconstruction
import com.lmarchery.core.scoring.archive.ArchiveSnapshot
import com.lmarchery.core.scoring.archive.DomainArchive
import com.lmarchery.core.scoring.archive.TargetProfileSnapshot
import com.lmarchery.core.scoring.archive.ArchiveImportPlan
import com.lmarchery.core.scoring.archive.ImportConflict
import com.lmarchery.core.scoring.archive.planArchiveImport
import com.lmarchery.core.scoring.archive.reconstructArchive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

data class StorageDiagnostic(val path: String, val detail: String, val archiveIssue: ArchiveIssue? = null)

sealed interface SessionReadResult {
    data object NotFound : SessionReadResult
    data class Found(val session: Session, val profile: TargetProfileSnapshot) : SessionReadResult
    data class NeedsIntervention(val diagnostic: StorageDiagnostic) : SessionReadResult
    data class TechnicalFailure(val cause: Exception) : SessionReadResult
}

sealed interface SessionDiscoveryResult {
    data class Found(val uuids: List<String>) : SessionDiscoveryResult
    data class TechnicalFailure(val cause: Exception) : SessionDiscoveryResult
}

sealed interface SessionCreateResult {
    data class Created(val uuid: String) : SessionCreateResult
    data class AlreadyExists(val uuid: String) : SessionCreateResult
    data class Conflict(val diagnostic: StorageDiagnostic) : SessionCreateResult
    data class NeedsIntervention(val diagnostic: StorageDiagnostic) : SessionCreateResult
    data class TechnicalFailure(val cause: Exception) : SessionCreateResult
}

sealed interface SessionUpdateResult {
    data class Updated(val uuid: String) : SessionUpdateResult
    data class Unchanged(val uuid: String) : SessionUpdateResult
    data object NotFound : SessionUpdateResult
    data class Conflict(val diagnostic: StorageDiagnostic) : SessionUpdateResult
    data class NeedsIntervention(val diagnostic: StorageDiagnostic) : SessionUpdateResult
    data class TechnicalFailure(val cause: Exception) : SessionUpdateResult
}

sealed interface ArchiveReadResult {
    data class Found(val archive: DomainArchive) : ArchiveReadResult
    data class NeedsIntervention(val diagnostic: StorageDiagnostic) : ArchiveReadResult
    data class TechnicalFailure(val cause: Exception) : ArchiveReadResult
}

/** SQL rows/DAOs stay internal; callers exchange complete, contextualized domain values. */
class RoomSessionStore internal constructor(private val database: SessionDatabase) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    /** Catalog foundation; JSON V1 deliberately does not back up these records yet. */
    val equipment: RoomEquipmentCatalog = RoomEquipmentCatalog(database) {
        check(!closed.get()) { "Session store is closed." }
    }

    private val archiveV2 = RoomArchiveV2(database) { check(!closed.get()) { "Session store is closed." } }

    private val archiveV3 = RoomArchiveV3(database) { check(!closed.get()) { "Session store is closed." } }
    suspend fun readArchiveV3(limits: com.lmarchery.core.scoring.archive.v3.ArchiveV3Limits = com.lmarchery.core.scoring.archive.v3.ArchiveV3Limits()): ArchiveV3ReadResult {
        val maximum = com.lmarchery.core.scoring.archive.v3.ArchiveV3Limits()
        require(limits.maxCollectionElements <= maximum.maxCollectionElements && limits.maxTotalElements <= maximum.maxTotalElements &&
            limits.maxTextLength <= maximum.maxTextLength) { "Storage V3 budgets can only be tightened." }
        return archiveV3.read(limits)
    }

    private val configuredWriter = RoomConfiguredWriter(database) { check(!closed.get()) { "Session store is closed." } }

    /** Domain creation only. Historical archive restoration is a separate, unavailable operation. */
    suspend fun createConfiguredSession(session: com.lmarchery.core.scoring.configured.ConfiguredSession): ConfiguredWriteResult =
        configuredWriter.write(ConfiguredWriteRequest.Create(session))

    /** Exact immutable revisions, including idempotent reconciliation after lost acknowledgement. */
    suspend fun updateConfiguredSession(expected: com.lmarchery.core.scoring.configured.ConfiguredSession,
        proposed: com.lmarchery.core.scoring.configured.ConfiguredSession): ConfiguredWriteResult =
        configuredWriter.write(ConfiguredWriteRequest.Update(expected, proposed))

    suspend fun readArchiveV2(): ArchiveV2ReadResult = archiveV2.read()
    suspend fun previewImportV2(archive: com.lmarchery.core.scoring.archive.DomainArchiveV2): ArchiveV2PreviewResult =
        archiveV2.preview(archive)
    suspend fun importArchiveV2(confirmed: ArchiveV2ImportPreview): ArchiveV2ImportResult = archiveV2.import(confirmed)

    /** All roots and descendants from one reader snapshot. No partial archive and no SQL writes. */
    suspend fun readArchive(): ArchiveReadResult = try {
        val callerContext = currentCoroutineContext()
        callerContext.ensureActive()
        check(!closed.get()) { "Session store is closed." }
        database.useReaderConnection { connection ->
            connection.deferredTransaction {
                val dao = database.records()
                dao.requireLegacyArchive()
                val profiles = linkedMapOf<String, TargetProfileSnapshot>()
                val sessions = mutableListOf<Session>()
                for (uuid in dao.sessionUuids()) {
                    callerContext.ensureActive()
                    val row = dao.session(uuid) ?: throw CorruptStorage(
                        StorageDiagnostic("sessions[$uuid]", "Registered session row is missing."))
                    val validated = try { validateStored(dao.sessionSnapshot(row)) }
                    catch (error: CorruptStorage) {
                        throw CorruptStorage(error.diagnostic.copy(path = "sessions[$uuid].${error.diagnostic.path}"))
                    }
                    val profile = validated.profiles.single()
                    val id = requireNotNull(profile.definition).uuid
                    dataCheck(profiles[id] == null || profiles[id] == profile,
                        "sessions[$uuid].profile", "Conflicting definitions for one profile identity.")
                    profiles[id] = profile
                    sessions += validated.sessions.single()
                }
                val complete = com.lmarchery.core.scoring.archive.snapshotArchive(
                    profiles.values.sortedBy { it.definition!!.uuid }, sessions)
                val result = validateStored(complete)
                callerContext.ensureActive()
                ArchiveReadResult.Found(result)
            }
        }
    } catch (error: CorruptStorage) { ArchiveReadResult.NeedsIntervention(error.diagnostic) }
    catch (error: CancellationException) { throw error }
    catch (error: Exception) { ArchiveReadResult.TechnicalFailure(error) }

    /** Preview reads a consistent snapshot and never reserves identities or inserts rows. */
    suspend fun previewImport(archive: DomainArchive): ArchiveImportPreviewResult = try {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        check(!closed.get()) { "Session store is closed." }
        val validated = validateStored(archive.toSnapshot())
        database.useReaderConnection { connection ->
            connection.deferredTransaction {
                val plan = importContext(validated, caller).second
                caller.ensureActive()
                ArchiveImportPreviewResult.Ready(ArchiveImportPreview(validated, plan))
            }
        }
    } catch (error: CorruptStorage) { ArchiveImportPreviewResult.NeedsIntervention(error.diagnostic) }
    catch (error: CancellationException) { throw error }
    catch (error: Exception) { ArchiveImportPreviewResult.TechnicalFailure(error) }

    /** One writer transaction for every new session. All conflicts are detected before the first INSERT. */
    suspend fun importArchive(confirmed: ArchiveImportPreview): ArchiveImportResult = try {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        check(!closed.get()) { "Session store is closed." }
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                caller.ensureActive()
                check(!closed.get()) { "Session store is closed." }
                val archive = validateStored(confirmed.archive.toSnapshot())
                val (before, plan) = importContext(archive, caller)
                if (plan != confirmed.plan || plan.conflicts.isNotEmpty()) {
                    ArchiveImportResult.Conflict(StorageDiagnostic("import", "Preview no longer valid or contains conflicts; select and preview again."))
                } else {
                    val dao = database.records()
                    val snapshot = archive.toSnapshot()
                    val ids = snapshot.identities()
                    val newSessions = snapshot.sessions.filter { it.uuid in plan.newSessionUuids }
                    val newProfiles = snapshot.profiles.filter { it.definition!!.uuid in plan.newProfileUuids }
                    val addedEntityUuids = newSessions.flatMap { session ->
                        listOf(session.uuid) + session.ends.flatMap { end -> listOf(end.uuid) + end.impacts.map { it.uuid } }
                    }.toSet()
                    val newIds = ids.filter { it.uuid in addedEntityUuids || it.kind == "PROFILE" && it.uuid in plan.newProfileUuids }
                    caller.ensureActive()
                    newIds.forEach { caller.ensureActive(); dao.insertIdentity(it) }
                    newProfiles.forEach { caller.ensureActive(); dao.writeProfile(it) }
                    newSessions.forEach { session ->
                        caller.ensureActive()
                        dao.writeSession(snapshot.copy(sessions = listOf(session)))
                    }
                    // Do not return a conflict after writes: any verification failure must THROW for rollback.
                    val (after, checked) = importContext(archive, caller)
                    dataCheck(checked.conflicts.isEmpty() && checked.newSessionUuids.isEmpty() &&
                        checked.identicalSessionUuids.size == archive.sessions.size && checked.newProfileUuids.isEmpty(),
                        "import.verification", "Persisted archive differs from accepted source; transaction rolled back.")
                    val oldSessions = before.sessions.map { it.uuid }.toSet()
                    val oldProfiles = before.profiles.map { it.definition!!.uuid }.toSet()
                    dataCheck(after.sessions.filter { it.uuid in oldSessions } == before.sessions &&
                        after.profiles.filter { it.definition!!.uuid in oldProfiles } == before.profiles &&
                        after.sessions.size == before.sessions.size + newSessions.size &&
                        after.profiles.size == before.profiles.size + newProfiles.size,
                        "import.verification", "Existing local content changed; transaction rolled back.")
                    caller.ensureActive()
                    ArchiveImportResult.Imported(plan.newSessionUuids.size, plan.identicalSessionUuids.size)
                }
            }
        }
    } catch (error: CorruptStorage) { ArchiveImportResult.NeedsIntervention(error.diagnostic) }
    catch (error: CancellationException) { throw error }
    catch (error: Exception) { ArchiveImportResult.TechnicalFailure(error) }

    /** Existing helpers keep full SQL validation. All queries share the caller's reader/writer transaction. */
    private suspend fun importContext(archive: DomainArchive, caller: CoroutineContext): Pair<DomainArchive, ArchiveImportPlan> {
        val dao = database.records()
        dao.requireLegacyArchive()
        val profiles = dao.profileUuids().map { uuid ->
            caller.ensureActive()
            val profile = dao.profileSnapshot(uuid)
            dataCheck(dao.identities(listOf(uuid)) == listOf(IdentityRow(uuid, "PROFILE")),
                "profiles[$uuid].identity", "Stored profile identity is missing or inconsistent.")
            profile
        }
        val sessions = dao.sessionUuids().map { uuid ->
            caller.ensureActive()
            val row = dao.session(uuid) ?: throw CorruptStorage(StorageDiagnostic("sessions[$uuid]", "Registered session row is missing."))
            validateStored(dao.sessionSnapshot(row)).sessions.single()
        }
        val known = validateStored(com.lmarchery.core.scoring.archive.snapshotArchive(profiles, sessions))
        val plan = planArchiveImport(archive, known)
        val knownIds = known.toSnapshot().identities().associateBy { it.uuid }
        val rowIds = dao.rowIdentities()
        dataCheck(rowIds.size == knownIds.size && rowIds.all { knownIds[it.uuid] == it },
            "identities", "Stored entity rows include duplicate or unreachable identities.")
        val registered = archive.toSnapshot().identities().chunked(900).flatMap { chunk -> dao.claimedIdentities(chunk.map { it.uuid }) }
        val collisions = registered.filter { knownIds[it.uuid] != it }.map {
            ImportConflict(it.uuid, "REGISTERED_IDENTITY_CONFLICT", "identities[${it.uuid}]", "UUID is already registered outside validated local data.")
        }
        caller.ensureActive()
        val collisionIds = collisions.mapNotNull { it.uuid }.toSet()
        val blockedSessions = archive.sessions.filter { s ->
            s.uuid in collisionIds || s.targetType.uuid in collisionIds || s.ends.any { end ->
                end.uuid in collisionIds || end.impacts.any { it.uuid in collisionIds }
            }
        }.map { it.uuid }.toSet()
        fun <T> owned(values: List<T>): List<T> = java.util.Collections.unmodifiableList(values.toList())
        return known to plan.copy(newSessionUuids = owned(plan.newSessionUuids.filterNot { it in blockedSessions }),
            identicalSessionUuids = owned(plan.identicalSessionUuids.filterNot { it in blockedSessions }),
            newProfileUuids = owned(plan.newProfileUuids.filterNot { it in collisionIds }),
            conflicts = owned(plan.conflicts + collisions))
    }

    /** Deterministic UUID discovery only. Each entry still requires contextualized read() validation. */
    suspend fun discover(): SessionDiscoveryResult = try {
        val callerContext = currentCoroutineContext()
        callerContext.ensureActive()
        check(!closed.get()) { "Session store is closed." }
        database.useReaderConnection { connection ->
            connection.deferredTransaction {
                val uuids = database.records().sessionUuids()
                callerContext.ensureActive()
                SessionDiscoveryResult.Found(uuids.toList())
            }
        }
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) { SessionDiscoveryResult.TechnicalFailure(error) }

    suspend fun read(uuid: String): SessionReadResult = try {
        check(!closed.get()) { "Session store is closed." }
        database.useReaderConnection { connection ->
            connection.deferredTransaction {
                val row = database.records().session(uuid)
                if (row == null) {
                    dataCheck(database.records().identities(listOf(uuid)).none { it.kind == "SESSION" },
                        "sessions[$uuid]", "Registered session row is missing.")
                    SessionReadResult.NotFound
                } else {
                    val archive = validateStored(database.records().sessionSnapshot(row))
                    SessionReadResult.Found(archive.sessions.single(), archive.profiles.single())
                }
            }
        }
    } catch (error: CorruptStorage) { SessionReadResult.NeedsIntervention(error.diagnostic) }
    catch (error: CancellationException) { throw error }
    catch (error: Exception) { SessionReadResult.TechnicalFailure(error) }

    /** One complete session and its exact profile. Same content is idempotent; no existing row is replaced. */
    suspend fun create(archive: DomainArchive): SessionCreateResult = try {
        check(!closed.get()) { "Session store is closed." }
        if (archive.sessions.size != 1 || archive.profiles.size != 1) {
            throw CorruptStorage(StorageDiagnostic("archive", "Creation requires exactly one session and its profile."))
        }
        // Recheck the public contract and snapshot assertions before touching any SQL row.
        val snapshot = archive.toSnapshot()
        val validated = validateStored(snapshot)
        database.useWriterConnection { connection ->
            connection.immediateTransaction { createInTransaction(validated, snapshot) }
        }
    } catch (error: CorruptStorage) { SessionCreateResult.NeedsIntervention(error.diagnostic) }
    catch (error: CancellationException) { throw error }
    catch (error: Exception) { SessionCreateResult.TechnicalFailure(error) }

    /** Optimistic local revision, including the exact profile definition and edition. No deletion or merge. */
    suspend fun update(expected: DomainArchive, proposed: DomainArchive): SessionUpdateResult = try {
        val callerContext = currentCoroutineContext()
        callerContext.ensureActive()
        check(!closed.get()) { "Session store is closed." }
        dataCheck(expected.sessions.size == 1 && expected.profiles.size == 1 &&
            proposed.sessions.size == 1 && proposed.profiles.size == 1,
            "archive", "Update requires exactly one expected and proposed session with its profile.")
        database.useWriterConnection { connection ->
            connection.immediateTransaction { updateInTransaction(expected, proposed, callerContext) }
        }
    } catch (error: CorruptStorage) { SessionUpdateResult.NeedsIntervention(error.diagnostic) }
    catch (error: CancellationException) { throw error }
    catch (error: Exception) { SessionUpdateResult.TechnicalFailure(error) }

    private suspend fun updateInTransaction(
        expected: DomainArchive,
        proposed: DomainArchive,
        callerContext: CoroutineContext,
    ): SessionUpdateResult {
        // Room changes coroutine context; check the original caller's Job before committing.
        callerContext.ensureActive()
        val dao = database.records()
        val uuid = expected.sessions.single().uuid
        val row = dao.session(uuid)
        callerContext.ensureActive()
        if (row == null) {
            dataCheck(dao.identities(listOf(uuid)).none { it.kind == "SESSION" },
                "sessions[$uuid]", "Registered session row is missing.")
            return SessionUpdateResult.NotFound
        }
        val previousSnapshot = dao.sessionSnapshot(row)
        val stored = validateStored(previousSnapshot)
        callerContext.ensureActive()
        if (!stored.sameContent(expected)) return SessionUpdateResult.Conflict(StorageDiagnostic(
            "sessions[$uuid]", "Stored session or profile differs from the complete expected state; reload before retrying."))
        if (stored.sameContent(proposed)) return SessionUpdateResult.Unchanged(uuid)

        val nextSnapshot = proposed.toSnapshot()
        val validated = validateStored(nextSnapshot) // Never use reconstructArchive(..., known) for local revisions.
        dataCheck(validated.profiles == stored.profiles, "profiles", "A persisted session cannot change profile definition or edition.")
        decode("sessions[$uuid].transition") {
            validateSessionUpdate(stored.sessions.single(), validated.sessions.single())
        }
        val previousIds = previousSnapshot.identities().map { it.uuid }.toSet()
        val newIds = nextSnapshot.identities().filterNot { it.uuid in previousIds }
        val collisions = newIds.chunked(900).flatMap { dao.claimedIdentities(it.map { identity -> identity.uuid }) }
        if (collisions.isNotEmpty()) return SessionUpdateResult.Conflict(StorageDiagnostic(
            "identities", "A new descendant UUID is already owned by stored data."))

        callerContext.ensureActive()
        newIds.forEach { dao.insertIdentity(it) }
        dao.updateSessionRows(previousSnapshot.sessionRows(), nextSnapshot.sessionRows())
        val reloaded = validateStored(dao.sessionSnapshot(requireNotNull(dao.session(uuid))))
        dataCheck(reloaded.sameContent(validated), "sessions[$uuid]",
            "Stored values differ from the proposed revision; transaction rolled back.")
        callerContext.ensureActive()
        return SessionUpdateResult.Updated(uuid)
    }

    private suspend fun createInTransaction(archive: DomainArchive, snapshot: ArchiveSnapshot): SessionCreateResult {
        val dao = database.records()
        val session = archive.sessions.single()
        val profile = archive.profiles.single()
        val previous = dao.session(session.uuid)
        if (previous != null) {
            val stored = validateStored(dao.sessionSnapshot(previous))
            return if (stored.sessions == archive.sessions && stored.profiles == archive.profiles) {
                SessionCreateResult.AlreadyExists(session.uuid)
            } else SessionCreateResult.Conflict(StorageDiagnostic("sessions[${session.uuid}]", "Existing session has different content; no overwrite allowed."))
        }
        val profileUuid = session.targetType.uuid
        val existingProfile = dao.profile(profileUuid)
        if (existingProfile != null) {
            val storedProfile = dao.profileSnapshot(profileUuid)
            // An unknown/custom stored definition must also remain immutable under its identity.
            validateStored(snapshot.copy(profiles = listOf(storedProfile), sessions = emptyList()))
            if (storedProfile != profile) return SessionCreateResult.Conflict(StorageDiagnostic("profiles[$profileUuid]",
                "Existing profile has a different complete definition or edition."))
        }
        val expected = snapshot.identities()
        val existing = expected.chunked(900).flatMap { dao.identities(it.map { identity -> identity.uuid }) }
        val claims = expected.chunked(900).flatMap { dao.claimedIdentities(it.map { identity -> identity.uuid }) }
        if (claims.any { it.uuid != profileUuid || existingProfile == null || it.kind != "PROFILE" }) {
            return SessionCreateResult.Conflict(StorageDiagnostic("identities", "An entity UUID is already owned by stored data."))
        }
        dataCheck(existingProfile == null || existing.any { it.uuid == profileUuid && it.kind == "PROFILE" },
            "profiles[$profileUuid].identity", "Stored profile identity is missing.")
        expected.filterNot { identity -> existing.any { it.uuid == identity.uuid } }.forEach { dao.insertIdentity(it) }
        if (existingProfile == null) dao.writeProfile(profile)
        dao.writeSession(snapshot)
        // A failed verification throws out of the transaction, ensuring rollback of every inserted row.
        val reloaded = validateStored(dao.sessionSnapshot(requireNotNull(dao.session(session.uuid))))
        dataCheck(reloaded.sessions == archive.sessions && reloaded.profiles == archive.profiles, "sessions[${session.uuid}]",
            "Stored values differ from the validated source; transaction rolled back.")
        return SessionCreateResult.Created(session.uuid)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) database.close()
    }
}

private fun DomainArchive.sameContent(other: DomainArchive): Boolean = sessions == other.sessions && profiles == other.profiles

private fun validateStored(snapshot: ArchiveSnapshot): DomainArchive = when (val result = reconstructArchive(snapshot)) {
    is ArchiveReconstruction.Accepted -> result.archive
    is ArchiveReconstruction.NeedsIntervention -> throw CorruptStorage(
        StorageDiagnostic(result.issue.path, result.issue.detail, result.issue))
}
