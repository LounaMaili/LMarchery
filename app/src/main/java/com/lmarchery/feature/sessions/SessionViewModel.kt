package com.lmarchery.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lmarchery.data.SessionRepository
import com.lmarchery.data.ArchiveSource
import com.lmarchery.data.ArchiveImporter
import com.lmarchery.data.archiveimport.ImportDocumentReader
import com.lmarchery.feature.archiveimport.ImportCoordinator
import com.lmarchery.feature.export.ExportCoordinator
import com.lmarchery.feature.export.ExportDocumentWriter

class SessionViewModel(repository: SessionRepository, source: ArchiveSource, writer: ExportDocumentWriter, importer: ArchiveImporter, reader: ImportDocumentReader) : ViewModel() {
    val controller = SessionController(repository, viewModelScope).also { it.refresh() }
    val exportCoordinator = ExportCoordinator(source, writer, viewModelScope,
        controller::acquireExport, controller::releaseExport)

    val importCoordinator = ImportCoordinator(reader, importer, viewModelScope,
        controller::acquireImport, controller::releaseArchiveOperation, controller::refresh)

    class Factory(private val repository: SessionRepository, private val source: ArchiveSource,
        private val writer: ExportDocumentWriter, private val importer: ArchiveImporter,
        private val reader: ImportDocumentReader) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == SessionViewModel::class.java)
            @Suppress("UNCHECKED_CAST")
            return SessionViewModel(repository, source, writer, importer, reader) as T
        }
    }
}
