package com.lmarchery.feature.configured

import com.lmarchery.backup.V3TestData as f
import com.lmarchery.core.database.*
import com.lmarchery.core.model.equipment.Equipment
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.data.*
import com.lmarchery.data.archiveimport.ImportDocumentReader
import com.lmarchery.feature.archiveimport.*
import com.lmarchery.feature.equipment.EquipmentController
import com.lmarchery.feature.export.*
import com.lmarchery.feature.sessions.*
import kotlinx.coroutines.*
import java.io.IOException
import kotlin.test.*

class ConfiguredEditorExclusionTest {
    @Test fun `real shared presentation lock excludes SAF catalog navigation while local or uncertain configured intent exists`() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            var exports=0; var writes=0
            val old=SessionController(object:SessionRepository {
                override suspend fun discover()=SessionDiscoveryResult.Found(emptyList())
                override suspend fun read(uuid:String):SessionReadResult=error("Locked historical read")
                override suspend fun create(archive:DomainArchive):SessionCreateResult=error("Locked create")
                override suspend fun update(expected:DomainArchive,proposed:DomainArchive):SessionUpdateResult=error("Locked update")
            },scope)
            val editor=ConfiguredSessionController(object:ConfiguredSessionRepository {
                override suspend fun readConfiguredSession(uuid:String)=ConfiguredSessionReadResult.NotFound
                override suspend fun createConfiguredSession(session:com.lmarchery.core.scoring.configured.ConfiguredSession):ConfiguredWriteResult {
                    writes++;return ConfiguredWriteResult.CommitUnconfirmed(ConfiguredWriteRequest.Create(session),IOException()) }
                override suspend fun updateConfiguredSession(expected:com.lmarchery.core.scoring.configured.ConfiguredSession,proposed:com.lmarchery.core.scoring.configured.ConfiguredSession):ConfiguredWriteResult=error("No revision")
            },scope,old::acquireImport,old::releaseArchiveOperation,old::refresh)
            val export=ExportCoordinator(ArchiveSource { error("No V1") },ExportDocumentWriter { _,_->error("No destination") },scope,
                old::acquireExport,old::releaseExport,dispatcher=Dispatchers.Unconfined,v3Source=ArchiveV3Source { exports++;ArchiveV3ReadResult.Found(f.empty()) })
            val importer=ImportCoordinator(ImportDocumentReader { error("Locked selection") },object:ArchiveImporter {
                override suspend fun previewImport(archive:DomainArchive):ArchiveImportPreviewResult=error("No V1")
                override suspend fun importArchive(preview:ArchiveImportPreview):ArchiveImportResult=error("No V1")
            },scope,old::acquireImport,old::releaseArchiveOperation,old::refresh,Dispatchers.Unconfined)
            val equipment=EquipmentController(object:EquipmentRepository {
                override suspend fun discover(kind:EquipmentKind)=EquipmentDiscoveryResult.Found(emptyList())
                override suspend fun read(kind:EquipmentKind,uuid:String):EquipmentReadResult<Equipment> = error("No catalog read")
                override suspend fun create(value:Equipment):EquipmentCreateResult=error("No catalog create")
                override suspend fun update(expected:Equipment,proposed:Equipment):EquipmentUpdateResult=error("No catalog revision")
            },scope,old::acquireImport,old::releaseArchiveOperation)
            editor.create()
            fun denied() {
                export.beginV3();importer.begin();equipment.open();old.configure();old.openSession(f.id(1))
                assertEquals(0,exports);assertFalse(equipment.state.value.open);assertEquals(SessionScreen.LIST,old.state.value.screen)
                assertTrue(old.state.value.archiveOperationLocked);assertFalse(old.acquireImport());assertFalse(old.acquireExport())
            }
            denied();editor.title(editor.state.value.form!!.token,"Synthetic");editor.confirmForm(editor.state.value.form!!.token)
            denied();assertEquals(1,writes);assertNotNull(editor.state.value.pending)
            editor.abandonSubmittedAndClose();assertFalse(old.state.value.archiveOperationLocked)
            export.beginV3();assertEquals(1,exports);editor.create();assertFalse(editor.state.value.open) // SAF owns lock now.
        } finally { scope.cancel() }
    }
}
