package com.lmarchery.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.lmarchery.feature.sessions.SessionViewModel
import com.lmarchery.ui.theme.LMarcheryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repository = (application as LMarcheryApplication).sessionRepository
        val viewModel = ViewModelProvider(this, SessionViewModel.Factory(repository))[SessionViewModel::class.java]

        setContent {
            LMarcheryTheme {
                LMarcheryApp(viewModel.controller)
            }
        }
    }
}
