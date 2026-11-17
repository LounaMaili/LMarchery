package com.lmarchery.core.database

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.archive.ArchiveGraph
import com.lmarchery.core.scoring.archive.v3.*
import com.lmarchery.core.scoring.archive.v4.*
import com.lmarchery.core.scoring.configured.ConfiguredSession
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

@Entity(tableName = "impact_cancellations", foreignKeys = [
    ForeignKey(entity = ImpactRow::class, parentColumns = ["uuid"], childColumns = ["impactUuid"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = EndRow::class, parentColumns = ["uuid"], childColumns = ["endUuid"], onDelete = ForeignKey.RESTRICT),
], indices = [Index(value = ["endUuid", "position"], unique = true)])
internal data class CancellationRow(@PrimaryKey val impactUuid: String, val endUuid: String, val position: Int,
    @Embedded(prefix = "cancelled_") val cancelledAt: SqlInstant, val reason: String, val comment: String?)

/** Additive only. Room owns the transaction; all twelve historical tables and their rows remain untouched. */
internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) = runBlocking {
        val q = StrictStorageQueries(connection)
        val before = collectArchiveV3(q, currentCoroutineContext(), ArchiveV3Limits())
        connection.prepare("""CREATE TABLE `impact_cancellations` (`impactUuid` TEXT NOT NULL, `endUuid` TEXT NOT NULL, `position` INTEGER NOT NULL, `cancelled_epochSecond` INTEGER, `cancelled_nano` INTEGER, `reason` TEXT NOT NULL, `comment` TEXT, PRIMARY KEY(`impactUuid`), FOREIGN KEY(`impactUuid`) REFERENCES `impacts`(`uuid`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`endUuid`) REFERENCES `ends`(`uuid`) ON UPDATE NO ACTION ON DELETE RESTRICT )""").use { it.step() }
        connection.prepare("CREATE UNIQUE INDEX `index_impact_cancellations_endUuid_position` ON `impact_cancellations` (`endUuid`, `position`)").use { it.step() }
        dataCheck(collectArchiveV4(q, currentCoroutineContext(), ArchiveV3Limits()) == convertArchiveV3ToV4(before),
            "migration.3_4", "The migrated complete graph differs from the exact V3 source.")
        connection.prepare("PRAGMA foreign_key_check").use { dataCheck(!it.step(), "migration.3_4.foreignKeys", "Invalid foreign key after migration.") }
    }
}

internal fun ImpactCancellation.row(position: Int) = CancellationRow(impactUuid, endUuid, position,
    SqlInstant(cancelledAt.epochSecond, cancelledAt.nano), reason.name, comment)

internal suspend fun SessionDao.writeCancellationDelta(previous: ConfiguredSession?, proposed: ConfiguredSession) {
    val old = previous?.ends.orEmpty().flatMap { it.end.cancellations }.associateBy { it.impactUuid }
    proposed.ends.forEach { e -> e.end.cancellations.forEachIndexed { index, proof ->
        val prior = old[proof.impactUuid]
        if (prior == null) insertCancellation(proof.row(index))
        else dataCheck(prior == proof, "cancellations[${proof.impactUuid}]", "Confirmed evidence is immutable.")
    } }
}

sealed interface ArchiveV4ReadResult {
    data class Found(val archive: DomainArchiveV4) : ArchiveV4ReadResult
    data class NeedsIntervention(val diagnostic: ArchiveV3StorageDiagnostic) : ArchiveV4ReadResult
    data class TechnicalFailure(val cause: Exception) : ArchiveV4ReadResult
}

internal fun validateStoredV4(source: ArchiveV4Snapshot, limits: ArchiveV3Limits = ArchiveV3Limits()): DomainArchiveV4 =
    when (val r = reconstructArchiveV4(source, limits = limits)) {
        is ArchiveV4Reconstruction.Accepted -> r.archive
        is ArchiveV4Reconstruction.NeedsIntervention -> throw CorruptV3Storage(ArchiveV3StorageDiagnostic(
            ArchiveV3StorageIssueKind.DOMAIN, r.issue.path, r.issue.detail,
            ArchiveV3Issue(r.issue.code, r.issue.path, r.issue.detail)))
    }

/** One caller-owned connection and snapshot, including every original record and every proof. */
internal suspend fun collectArchiveV4(queries: StrictStorageQueries, caller: CoroutineContext,
    limits: ArchiveV3Limits): DomainArchiveV4 {
    val base = collectArchiveV3Payload(queries, caller, limits)
    val rows = queries.cancellations(limits)
    val rowsByEnd = rows.groupBy { it.endUuid }
    val snapshots = base.sessions.flatMap { s -> s.ends.flatMap { e ->
        val owned = rowsByEnd[e.uuid].orEmpty()
        positions(owned.map { it.position }, owned.size, "ends[${e.uuid}].cancellations")
        owned.map { row -> decode("cancellations[${row.impactUuid}]") {
            ImpactCancellationSnapshot(row.endUuid, row.impactUuid, row.cancelledAt.text(),
                enumValueOf<ImpactCancellationReason>(row.reason), row.comment)
        } }
    } }
    dataCheck(snapshots.size == rows.size, "cancellations", "Cancellation has no reachable owner.")
    val payload = base.toSnapshot()
    caller.ensureActive()
    return validateStoredV4(ArchiveV4Snapshot(4, 1, payload.profiles, payload.sessions, payload.bows, payload.arrowSets, snapshots), limits)
}

internal fun ArchiveGraph.hasCancellations() = sessions.any { s -> s.ends.any { it.end.cancellations.isNotEmpty() } }

internal suspend fun SessionDao.cancellationConflict(uuids: List<String>): StorageDiagnostic? {
    val found = uuids.chunked(900).firstNotNullOfOrNull { cancelledAmong(it).firstOrNull() } ?: return null
    return StorageDiagnostic("impacts[$found].cancellation",
        "CONFLIT_ANNULATION : cet impact connu a été annulé. Une ancienne archive ne peut pas le réintroduire comme actif ; aucun remplacement.")
}
