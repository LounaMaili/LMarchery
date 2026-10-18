package com.lmarchery.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lmarchery.data.EquipmentRepository
import com.lmarchery.feature.equipment.EquipmentController
import com.lmarchery.data.SessionRepository
import com.lmarchery.data.ArchiveSource
import com.lmarchery.data.ArchiveImporter
import com.lmarchery.data.ArchiveV2Importer
import com.lmarchery.data.ArchiveV2Source
import com.lmarchery.data.archiveimport.ImportDocumentReader
import com.lmarchery.feature.archiveimport.ImportCoordinator
import com.lmarchery.feature.export.ExportCoordinator
import com.lmarchery.feature.export.ExportDocumentWriter

class SessionViewModel(repository: SessionRepository, source: ArchiveSource, writer: ExportDocumentWriter, importer: ArchiveImporter, reader: ImportDocumentReader, v2Source: ArchiveV2Source? = null, v2Importer: ArchiveV2Importer? = null, equipmentRepository: EquipmentRepository? = null) : ViewModel() {
    val controller = SessionController(repository, viewModelScope).also { it.refresh() }
    val equipmentController = equipmentRepository?.let { EquipmentController(it, viewModelScope, controller::acquireImport, controller::releaseArchiveOperation) }
    val exportCoordinator = ExportCoordinator(source, writer, viewModelScope,
        controller::acquireExport, controller::releaseExport, v2Source = v2Source)

    val importCoordinator = ImportCoordinator(reader, importer, viewModelScope,
        controller::acquireImport, controller::releaseArchiveOperation, controller::refresh, v2Repository = v2Importer)

    class Factory(private val repository: SessionRepository, private val source: ArchiveSource,
        private val writer: ExportDocumentWriter, private val importer: ArchiveImporter,
        private val reader: ImportDocumentReader, private val v2Source: ArchiveV2Source? = null,
        private val v2Importer: ArchiveV2Importer? = null, private val equipmentRepository: EquipmentRepository? = null) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == SessionViewModel::class.java)
            @Suppress("UNCHECKED_CAST")
            return SessionViewModel(repository, source, writer, importer, reader, v2Source, v2Importer, equipmentRepository) as T
        }
    }
}
