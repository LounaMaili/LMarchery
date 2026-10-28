package com.lmarchery.core.database

import com.lmarchery.core.scoring.configured.ConfiguredSession
import java.util.concurrent.CancellationException

/** Keep this exact immutable intent until confirmation or explicit reconciliation; never rebase a retry. */
sealed interface ConfiguredWriteRequest {
    val proposed: ConfiguredSession
    data class Create(override val proposed: ConfiguredSession) : ConfiguredWriteRequest
    data class Update(val expected: ConfiguredSession, override val proposed: ConfiguredSession) : ConfiguredWriteRequest
}

enum class ConfiguredWriteConflict { IDENTITY_OCCUPIED, REVISION, NOT_FOUND, NOT_CONFIGURED, INVALID_TRANSITION, INVALID_PROVENANCE }
enum class ConfiguredCommitOutcome { NOT_ATTEMPTED, UNCONFIRMED }

/** Confirmation is returned only after Room has acknowledged the transaction. */
sealed interface ConfiguredWriteResult {
    val request: ConfiguredWriteRequest
    data class Created(override val request: ConfiguredWriteRequest.Create) : ConfiguredWriteResult
    data class Updated(override val request: ConfiguredWriteRequest.Update) : ConfiguredWriteResult
    /** Exact proposed content and CONFIGURED representation found under the writer lock; zero DML. */
    data class AlreadyApplied(override val request: ConfiguredWriteRequest) : ConfiguredWriteResult
    data class Conflict(override val request: ConfiguredWriteRequest, val kind: ConfiguredWriteConflict,
        val diagnostic: StorageDiagnostic) : ConfiguredWriteResult
    data class NeedsIntervention(override val request: ConfiguredWriteRequest, val diagnostic: ArchiveV3StorageDiagnostic,
        val commitOutcome: ConfiguredCommitOutcome) : ConfiguredWriteResult
    /** No business write was attempted by this call. This does not establish a previous call's outcome. */
    data class TechnicalFailure(override val request: ConfiguredWriteRequest, val cause: Exception) : ConfiguredWriteResult
    /** A write was attempted and confirmation was lost. Re-read/compare exact expected and proposed, never blindly replay. */
    data class CommitUnconfirmed(override val request: ConfiguredWriteRequest, val cause: Exception) : ConfiguredWriteResult
}

/** Cancellation still propagates. A caller must retain its intent even if its Job masks this exception. */
class ConfiguredWriteCancelled(val request: ConfiguredWriteRequest, val commitOutcome: ConfiguredCommitOutcome,
    cause: CancellationException) : CancellationException("Configured write cancelled; commit outcome: $commitOutcome") {
    init { initCause(cause) }
}
