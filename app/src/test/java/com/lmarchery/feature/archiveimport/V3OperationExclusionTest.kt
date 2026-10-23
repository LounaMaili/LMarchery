package com.lmarchery.feature.archiveimport

import com.lmarchery.backup.V3TestData as f
import com.lmarchery.core.database.*
import com.lmarchery.core.export.*
import com.lmarchery.core.model.equipment.Equipment
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.archive.v3.*
import com.lmarchery.data.*
import com.lmarchery.data.archiveimport.ImportDocumentReader
import com.lmarchery.feature.equipment.EquipmentController
import com.lmarchery.feature.export.*
import com.lmarchery.feature.sessions.*
import kotlinx.coroutines.*
import java.io.IOException
import kotlin.test.*

class V3OperationExclusionTest {
    @Test fun `uncertain V3 restore excludes real controller navigation catalog and export until deliberate resolution`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val source = f.archive(); var previews = 0; var exportReads = 0; var commits = 0
            val controller = SessionController(object : SessionRepository {
                override suspend fun discover() = SessionDiscoveryResult.Found(emptyList())
                override suspend fun read(uuid: String): SessionReadResult = error("Locked read")
                override suspend fun create(archive: DomainArchive): SessionCreateResult = error("Locked write")
                override suspend fun update(expected: DomainArchive, proposed: DomainArchive): SessionUpdateResult = error("Locked write")
            }, scope)
            val equipment = EquipmentController(object : EquipmentRepository {
                override suspend fun discover(kind: EquipmentKind) = EquipmentDiscoveryResult.Found(emptyList())
                override suspend fun read(kind: EquipmentKind, uuid: String): EquipmentReadResult<Equipment> = error("No read")
                override suspend fun create(value: Equipment): EquipmentCreateResult = error("No write")
                override suspend fun update(expected: Equipment, proposed: Equipment): EquipmentUpdateResult = error("No write")
            }, scope, controller::acquireImport, controller::releaseArchiveOperation)
            val export = ExportCoordinator(ArchiveSource { error("No V1") }, ExportDocumentWriter { _, _ -> error("No destination") },
                scope, controller::acquireExport, controller::releaseExport, dispatcher = Dispatchers.Unconfined,
                v3Source = ArchiveV3Source { exportReads++; ArchiveV3ReadResult.Found(source) })
            val importer = ImportCoordinator(ImportDocumentReader { ArchiveV3JsonCodec().encode(source) }, object : ArchiveImporter {
                override suspend fun previewImport(archive: DomainArchive): ArchiveImportPreviewResult = error("No V1")
                override suspend fun importArchive(preview: ArchiveImportPreview): ArchiveImportResult = error("No V1")
            }, scope, controller::acquireImport, controller::releaseArchiveOperation, controller::refresh, Dispatchers.Unconfined,
                v3Repository = ArchiveV3Importer { archive -> previews++; PreparedV3Result.Ready(object : PreparedV3Import {
                    override val archive = archive
                    override val plan = planArchiveImportV3(archive, f.empty())
                    override suspend fun commit(): RestoreV3Result { commits++; return RestoreV3Result.CommitUnconfirmed(IOException("Synthetic lost ACK")) }
                }) })
            importer.begin(); val ticket = assertIs<ImportState.Choosing>(importer.state.value).requestId
            importer.claimSelection(); importer.selected(ticket, "content://synthetic/v3")
            importer.confirmV3(assertIs<ImportState.PreviewV3>(importer.state.value).confirmationId)
            assertTrue(controller.state.value.archiveOperationLocked); equipment.open(); assertFalse(equipment.state.value.open)
            controller.openSession(f.id(1)); controller.configure(); assertEquals(SessionScreen.LIST, controller.state.value.screen)
            export.beginV3(); assertIs<ExportState.Failed>(export.state.value); assertEquals(0, exportReads)
            assertTrue(controller.state.value.archiveOperationLocked); assertEquals(1, commits)
            importer.repreviewV3(); assertEquals(2, previews); assertEquals(1, commits)
            importer.cancel(); assertFalse(controller.state.value.archiveOperationLocked)
            equipment.open(); assertTrue(equipment.state.value.open); export.beginV3(); assertEquals(0, exportReads)
            equipment.close(); export.beginV3(); assertIs<ExportState.Choosing>(export.state.value); assertEquals(1, exportReads)
        } finally { scope.cancel() }
    }
}
