package com.lmarchery.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.lmarchery.feature.sessions.SessionViewModel
import com.lmarchery.data.export.AndroidDocumentAccess
import com.lmarchery.data.export.VerifyingDocumentWriter
import com.lmarchery.data.archiveimport.AndroidImportDocumentAccess
import com.lmarchery.data.archiveimport.BoundedDocumentReader
import com.lmarchery.ui.theme.LMarcheryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repository = (application as LMarcheryApplication).sessionRepository
        val writer = VerifyingDocumentWriter(AndroidDocumentAccess(applicationContext.contentResolver))
        val reader = BoundedDocumentReader(AndroidImportDocumentAccess(applicationContext.contentResolver))
        val viewModel = ViewModelProvider(this, SessionViewModel.Factory(repository, repository, writer, repository, reader, repository, repository, repository.equipment))[SessionViewModel::class.java]

        setContent {
            LMarcheryTheme {
                LMarcheryApp(viewModel.controller, viewModel.exportCoordinator, viewModel.importCoordinator, viewModel.equipmentController)
            }
        }
    }
}
