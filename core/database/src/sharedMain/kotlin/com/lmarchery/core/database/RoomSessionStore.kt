package com.lmarchery.core.database

import androidx.room.immediateTransaction
import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import com.lmarchery.core.model.session.Session
import com.lmarchery.core.scoring.archive.ArchiveIssue
import com.lmarchery.core.scoring.archive.ArchiveReconstruction
import com.lmarchery.core.scoring.archive.ArchiveSnapshot
import com.lmarchery.core.scoring.archive.DomainArchive
import com.lmarchery.core.scoring.archive.TargetProfileSnapshot
import com.lmarchery.core.scoring.archive.reconstructArchive
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

data class StorageDiagnostic(val path: String, val detail: String, val archiveIssue: ArchiveIssue? = null)

sealed interface SessionReadResult {
    data object NotFound : SessionReadResult
    data class Found(val session: Session, val profile: TargetProfileSnapshot) : SessionReadResult
    data class NeedsIntervention(val diagnostic: StorageDiagnostic) : SessionReadResult
    data class TechnicalFailure(val cause: Exception) : SessionReadResult
}

sealed interface SessionCreateResult {
    data class Created(val uuid: String) : SessionCreateResult
    data class AlreadyExists(val uuid: String) : SessionCreateResult
    data class Conflict(val diagnostic: StorageDiagnostic) : SessionCreateResult
    data class NeedsIntervention(val diagnostic: StorageDiagnostic) : SessionCreateResult
    data class TechnicalFailure(val cause: Exception) : SessionCreateResult
}

/** Creation only. SQL rows/DAOs stay internal; callers exchange validated domain values. */
class RoomSessionStore internal constructor(private val database: SessionDatabase) : AutoCloseable {
    private val closed = AtomicBoolean(false)

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
        if (existing.any { it.uuid != profileUuid || existingProfile == null || it.kind != "PROFILE" }) {
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

private fun validateStored(snapshot: ArchiveSnapshot): DomainArchive = when (val result = reconstructArchive(snapshot)) {
    is ArchiveReconstruction.Accepted -> result.archive
    is ArchiveReconstruction.NeedsIntervention -> throw CorruptStorage(
        StorageDiagnostic(result.issue.path, result.issue.detail, result.issue))
}
