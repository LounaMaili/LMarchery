package com.lmarchery.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lmarchery.data.SessionRepository
import com.lmarchery.data.ArchiveSource
import com.lmarchery.feature.export.ExportCoordinator
import com.lmarchery.feature.export.ExportDocumentWriter

class SessionViewModel(repository: SessionRepository, source: ArchiveSource, writer: ExportDocumentWriter) : ViewModel() {
    val controller = SessionController(repository, viewModelScope).also { it.refresh() }
    val exportCoordinator = ExportCoordinator(source, writer, viewModelScope,
        controller::acquireExport, controller::releaseExport)

    class Factory(private val repository: SessionRepository, private val source: ArchiveSource,
        private val writer: ExportDocumentWriter) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == SessionViewModel::class.java)
            @Suppress("UNCHECKED_CAST")
            return SessionViewModel(repository, source, writer) as T
        }
    }
}
